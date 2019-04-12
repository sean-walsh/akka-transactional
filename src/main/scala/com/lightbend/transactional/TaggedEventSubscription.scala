package com.lightbend.transactional

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.persistence.query.{Offset, PersistenceQuery}
import akka.stream.ActorMaterializer
import com.lightbend.transactional.PersistentTransactionEvents.TransactionalEventEnvelope

import scala.concurrent.ExecutionContext

object TaggedEventSubscription {
  final val ActorNamePrefix = "tagged-event-subscription-"

  def props(eventTag: String, subscriber: ActorRef): Props =
    Props(new TaggedEventSubscription(eventTag, subscriber))
}

/**
  * Subscribes to tagged events and issues those events to the event log.
  */
class TaggedEventSubscription(eventTag: String, subscriber: ActorRef) extends Actor with ActorLogging {

  private val query = readJournal()
  implicit val mat: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = context.dispatcher

  query.eventsByTag(eventTag, Offset.noOffset).map(_.event).runForeach {
    case envelope: TransactionalEventEnvelope =>
      subscriber ! envelope
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
