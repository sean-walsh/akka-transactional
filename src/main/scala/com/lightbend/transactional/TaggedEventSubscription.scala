package com.lightbend.transactional

import akka.actor.{Actor, Props, ReceiveTimeout}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.persistence.query.{Offset, PersistenceQuery}
import akka.stream.ActorMaterializer
import com.lightbend.transactional.PersistentSagaActorEvents.TransactionalEventEnvelope

import scala.concurrent.duration._

object TaggedEventSubscription {
  final val ActorNamePrefix = "tagged-event-subscription-"
}

/**
  * Subscribes to tagged events and issues those events to the event log.
  */
abstract class TaggedEventSubscription(eventTag: String) extends Actor {

  private val query = readJournal()
  implicit val mat: ActorMaterializer = ActorMaterializer()

  query.eventsByTag(eventTag, Offset.noOffset).map(_.event).runForeach {
    case envelope: TransactionalEventEnvelope =>
      context.actorSelection(s"/user/${PersistentSagaActor.RegionName}") ! envelope // TODO: figure out retry
  }

  override def receive: Receive = Actor.emptyBehavior

  private def readJournal(): EventsByTagQuery = {
    val journalIdentifier =
      if (context.system.settings.config.getString("akka.persistence.journal.plugin").
        contains("cassandra"))
        CassandraReadJournal.Identifier
      else
        LeveldbReadJournal.Identifier
    PersistenceQuery(context.system).readJournalFor[EventsByTagQuery](journalIdentifier)
  }
}

/**
  * nodeEventTag
  */
object NodeTaggedEventSubscription {
  def props(nodeEventTag: String): Props = Props(new NodeTaggedEventSubscription(nodeEventTag))
}

/**
  * One per node implementation. This will be up and running as long as the current node is up and running.
  */
class NodeTaggedEventSubscription(nodeEventTag: String) extends TaggedEventSubscription(nodeEventTag)

/**
  * Companion
  */
case object TransientTaggedEventSubscription {
  case class TransientTaggedEventSubscriptionTimedOut(transientEventTag: String)

  def props(transientEventTag: String): Props = Props(new TransientTaggedEventSubscription(transientEventTag))
}

/**
  * For transient subscriptions that originated on another node. This will always look to timeout.
  * When it does time out it will issue and event to the event log in case an interested party (saga) is stalled
  * and still needs a subscription. In that case it will just be restarted by that party until it times out
  * again and so on until the saga has completed.
  */
class TransientTaggedEventSubscription(transientEventTag: String) extends TaggedEventSubscription(transientEventTag) {

  import TransientTaggedEventSubscription._

  val duration: FiniteDuration = context.system.settings.config
    .getDuration("akka-saga.saga.transient-event-subscription-timeout").toNanos.nanos

  context.setReceiveTimeout(duration)

  override def receive: Receive = {
    case ReceiveTimeout =>
      context.system.eventStream.publish(TransientTaggedEventSubscriptionTimedOut(transientEventTag))
      context.stop(self)
  }
}