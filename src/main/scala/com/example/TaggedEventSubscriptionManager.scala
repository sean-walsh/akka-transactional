package com.example

import akka.NotUsed
import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorRef, OneForOneStrategy, Props, ReceiveTimeout, Terminated}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{EventEnvelope, Offset, PersistenceQuery}
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.Timeout
import com.example.PersistentSagaActor.TransactionalEventEnvelope

import scala.concurrent.duration._

/**
  * Companion to EventSubscriptionNodeSingleton.
  */
object TaggedEventSubscriptionManager {

  case class SubscribeToTaggedEvent(key: String, eventTag: String, offset: Long)

  case class UnsubscribeFromTaggedEvent(key: String, eventTag: String)

  case class EventConfirmed(key: String, envelope: TransactionalEventEnvelope)

  case class UpdateOffset(key: String, eventTag: String, offset: Long)

  case object StopEventSubscriptionNodeSingleton

  def props(): Props = Props(new TaggedEventSubscriptionManager)
}

/**
  * This actor will subscribe to only one event at a time and only subscribe to the next event (offset) after
  * the previous has been confirmed to have been received.
  */
class TaggedEventKeyedSubscriber(key: String, eventTag: String, initialOffset: Long)(implicit timeout: Timeout) extends Actor {

  import TaggedEventSubscriptionManager._

  private val query = readJournal()
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  var source: Source[EventEnvelope, NotUsed] = null

  // Timeout in case a persistent saga has moved elsewhere in the cluster.
  context.setReceiveTimeout(timeout.duration)

  updateOffsetAndSubscribe(initialOffset)

  override def receive: Receive = {
    case UpdateOffset(_, _, offset) =>
      context.setReceiveTimeout(timeout.duration)
      updateOffsetAndSubscribe(offset)

    case ReceiveTimeout =>
      context.stop(self)
  }

  private def readJournal(): EventsByTagQuery = {
    val journalIdentifier =
      if (context.system.settings.config.getString("akka.persistence.journal.plugin").
        contains("cassandra"))
          CassandraReadJournal.Identifier
      else
        LeveldbReadJournal.Identifier
    PersistenceQuery(context.system).readJournalFor[EventsByTagQuery](journalIdentifier)
  }

  private def updateOffsetAndSubscribe(offset: Long): Unit = {
    source = query.eventsByTag(eventTag, Offset.sequence(offset))
    source.map(_.event).limit(1).runForeach {
      case envelope: TransactionalEventEnvelope => context.system.eventStream.publish(EventConfirmed(key, envelope))
    }
  }
}

/**
  * There should be one of these instantiated for every node.
  * --By way of confirmation, the client must acknowledge receipt of an event by sending an updated offset.
  */
class TaggedEventSubscriptionManager extends Actor {

  import TaggedEventSubscriptionManager._

  // How often to retry transactions on an entity when no confirmation received.
  private val configuredTimeout: FiniteDuration =
    context.system.settings.config.getDuration("akka-saga.event-subscriber.timeout-subscription-after").toNanos.nanos
  implicit def timeout: Timeout = Timeout(configuredTimeout)

  private var subscriptions: Map[String, ActorRef] = Map.empty

  override val supervisorStrategy =
    OneForOneStrategy() {
      case _: Exception => Stop
    }

  override def receive: Receive = {
    case msg @ SubscribeToTaggedEvent(key, eventTag, offset) =>
      subscriptions.get(eventTag) match {
        case Some(s) =>
          s forward msg
        case None =>
          createChild(key, eventTag, offset) ! msg
      }

    case msg @ UpdateOffset(key, eventTag, offset) =>
      subscriptions.get(fullKey(key, eventTag)) match {
        case Some(s) =>
          s forward msg
        case None =>
          createChild(key, eventTag, offset) ! SubscribeToTaggedEvent(key, eventTag, offset)
      }

    case Terminated(child) =>
      subscriptions = subscriptions.filterNot(_._2 == child)

    case StopEventSubscriptionNodeSingleton =>
      context.stop(self)
  }

  /**
    * Creates a child representing an event tag subscription and adds it to subscriptions map.
    */
  private def createChild(key: String, eventTag: String, offset: Long): ActorRef = {
    val child = context.actorOf(Props(new TaggedEventKeyedSubscriber(key, eventTag, offset)), fullKey(key, eventTag))
    context.watch(child)
    subscriptions = subscriptions + (fullKey(key, eventTag) -> child)
    child
  }

  private def fullKey(key: String, eventTag: String) =
    key + "-tagged-event-subscriber"
}
