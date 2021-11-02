package com.akkatransactional.classic

import PersistentTransactionCommands.TransactionalCommand

/**
  * Wrapping "Envelope" events to be handled by entities participating in a transaction.
  */
object PersistentTransactionEvents {

  trait PersistentTransactionEvent {
    def transactionId: String
    def entityId: String
  }

  /** Events on a transaction itself **/
  case class TransactionStarted(transactionId: String, description: String)
    extends PersistentTransactionEvent {
    override val entityId = transactionId
  }
  case class PersistentTransactionComplete(transactionId: String) extends PersistentTransactionEvent {
    override val entityId = transactionId
  }
  case class StreamingTransactionStarted(transactionId: String, description: String, nodeEventTag: String)
    extends PersistentTransactionEvent {
    override val entityId = transactionId
  }
  case class StreamingCommandAdded(transactionId: String, command: TransactionalCommand, sequence: Long)
    extends PersistentTransactionEvent {
    override val entityId = transactionId
  }
  case class StreamingCommandsEnded(transactionId: String, sequence: Long) extends PersistentTransactionEvent {
    override val entityId = transactionId
  }

  /** Events on entities **/
  // Envelope to wrap events.
  trait TransactionalEventEnvelope extends PersistentTransactionEvent

  // Transactional event wrappers.
  case class EntityTransactionStarted(transactionId: String, entityId: String, event: TransactionalEvent)
    extends TransactionalEventEnvelope
  case class TransactionCleared(transactionId: String, entityId: String, event: TransactionalEvent)
    extends TransactionalEventEnvelope
  case class TransactionReversed(transactionId: String, entityId: String, event: TransactionalEvent)
    extends TransactionalEventEnvelope

  // Trait for any entity events participating in a transaction.
  trait TransactionalEvent

  // Trait for any entity events participating in a transaction that are exceptions.
  trait TransactionalExceptionEvent extends TransactionalEvent
}
