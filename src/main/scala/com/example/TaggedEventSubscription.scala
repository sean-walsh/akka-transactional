package com.example

import akka.NotUsed
import akka.actor.Actor
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{EventEnvelope, Offset, PersistenceQuery}
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import com.example.PersistentSagaActor.TransactionalEventEnvelope

/**
  * Companion.
  */
object TaggedEventSubscription {
  case class EventConfirmed(envelope: TransactionalEventEnvelope)
}

/**
  * Mix this trait in to subscribe to entity events.
  */
trait TaggedEventSubscription { this: Actor =>

  import TaggedEventSubscription._

  def eventTag: String

  protected def subscribeToEvents(): Unit = {
    implicit val materializer = ActorMaterializer()
    val query = readJournal()
    val source: Source[EventEnvelope, NotUsed] = query.eventsByTag(eventTag, Offset.noOffset)
    // FIXME to preserve backpressure this should use `source.map(_.event).ask` and the actor should reply when done, e.g. with akka.Done
    source.map(_.event).runForeach {
      case envelope: TransactionalEventEnvelope => self ! EventConfirmed(envelope)
    }
  }

  private def readJournal(): EventsByTagQuery = {
    val journalIdentifier =
      if (context.system.settings.config.getString("akka.persistence.journal.plugin").contains("cassandra"))
        CassandraReadJournal.Identifier
      else
        LeveldbReadJournal.Identifier
    PersistenceQuery(context.system).readJournalFor[EventsByTagQuery](journalIdentifier)
  }

}
