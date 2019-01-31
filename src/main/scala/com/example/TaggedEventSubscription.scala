package com.example

import akka.actor.{Actor, Props}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{Offset, PersistenceQuery}
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.stream.ActorMaterializer
import com.example.PersistentSagaActor.TransactionalEventEnvelope

/**
  * Companion to EventSubscriptionNodeSingleton.
  */
object TaggedEventSubscription {

  case class EventConfirmed(eventTag: EventTag, transactionId: TransactionId, envelope: TransactionalEventEnvelope)

  def props(eventTag: EventTag): Props = Props(new TaggedEventSubscription(eventTag))
}

/**
  * Subscribes to tagged events.
  * There should be one of these instantiated for every node, each working with a unique tag
  * per node.
  */
class TaggedEventSubscription(eventTag: EventTag) extends Actor {

  import TaggedEventSubscription._

  private val query = readJournal()
  implicit val mat: ActorMaterializer = ActorMaterializer()

  query.eventsByTag(eventTag, Offset.noOffset).map(_.event).runForeach {
    case envelope: TransactionalEventEnvelope =>
      context.system.eventStream.publish(EventConfirmed(eventTag, envelope.transactionId, envelope))
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
