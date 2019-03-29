package com.lightbend.transactional

import com.lightbend.transactional.PersistentSagaActorCommands.TransactionalCommand

/**
  * Wrapping "Envelope" events to be handled by entities participating in a saga.
  */
object PersistentSagaActorEvents {

  /** Events on a saga **/

  trait SagaEvent {
    def transactionId: String
  }
  case class SagaStarted(transactionId: String, description: String, nodeEventTag: String,
                         commands: Seq[TransactionalCommand]) extends SagaEvent
  case class StreamingSagaStarted(transactionId: String, description: String, nodeEventTag: String) extends SagaEvent
  case class SagaCommandAdded(transactionId: String, command: TransactionalCommand) extends SagaEvent
  case class StreamingSagaEnded(transactionId: String)
  case class SagaTransactionComplete(transactionId: String) extends SagaEvent

  /** Events on entities **/
  // Envelope to wrap events.
  trait TransactionalEventEnvelope {
    def transactionId: String
    def entityId: String
  }

  // Transactional event wrappers.
  case class TransactionStarted(transactionId: String, entityId: String, eventTag: String, event: TransactionalEvent)
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
