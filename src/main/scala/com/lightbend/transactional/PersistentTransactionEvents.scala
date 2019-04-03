package com.lightbend.transactional

import com.lightbend.transactional.PersistentTransactionCommands.TransactionalCommand

/**
  * Wrapping "Envelope" events to be handled by entities participating in a saga.
  */
object PersistentTransactionEvents {

  trait SagaEvent {
    def transactionId: String
    def entityId: String
  }

  /** Events on a transaction itself **/
  case class TransactionStarted(transactionId: String, description: String, nodeEventTag: String,
                                commands: Seq[TransactionalCommand]) extends SagaEvent {
    override val entityId = transactionId
  }
  case class SagaTransactionComplete(transactionId: String) extends SagaEvent {
    override val entityId = transactionId
  }

  /** Events on entities **/
  // Envelope to wrap events.
  trait TransactionalEventEnvelope extends SagaEvent

  // Transactional event wrappers.
  case class EntityTransactionStarted(transactionId: String, entityId: String, eventTag: String, event: TransactionalEvent)
    extends TransactionalEventEnvelope
  case class TransactionCleared(transactionId: String, entityId: String, eventTag: String)
    extends TransactionalEventEnvelope
  case class TransactionReversed(transactionId: String, entityId: String, eventTag: String)
    extends TransactionalEventEnvelope

  // Trait for any entity events participating in a saga.
  trait TransactionalEvent

  // Trait for any entity events participating in a saga that are exceptions.
  trait TransactionalExceptionEvent extends TransactionalEvent
}
