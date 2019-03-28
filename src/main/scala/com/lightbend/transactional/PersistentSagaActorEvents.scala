package com.lightbend.transactional

import com.lightbend.transactional.PersistentSagaActorCommands.{AddSagaCommand, TransactionalCommand}
import com.lightbend.transactional.lightbend.{EntityId, TransactionId}

/**
  * Wrapping "Envelope" events to be handled by entities participating in a saga.
  */
object PersistentSagaActorEvents {

  /** Events on a saga **/

  sealed trait SagaEvent {
    def transactionId: TransactionId
  }
  case class SagaStarted(transactionId: TransactionId, description: String, nodeEventTag: String,
                         commands: Seq[TransactionalCommand]) extends SagaEvent
  case class StreamingSagaStarted(transactionId: TransactionId, description: String, nodeEventTag: String) extends SagaEvent
  case class SagaCommandAdded(transactionId: TransactionId, command: TransactionalCommand) extends SagaEvent

  /** Events on entities **/
  // Envelope to wrap events.
  trait TransactionalEventEnvelope {
    def transactionId: TransactionId
    def entityId: EntityId
  }

  // Transactional event wrappers.
  case class TransactionStarted(transactionId: TransactionId, entityId: EntityId, eventTag: String, event: TransactionalEvent)
    extends TransactionalEventEnvelope
  case class TransactionCleared(transactionId: TransactionId, entityId: EntityId, eventTag: String)
    extends TransactionalEventEnvelope
  case class TransactionReversed(transactionId: TransactionId, entityId: EntityId, eventTag: String)
    extends TransactionalEventEnvelope

  // Trait for any entity events participating in a saga.
  trait TransactionalEvent

  // Trait for any entity events participating in a saga that are exceptions.
  trait TransactionalExceptionEvent extends TransactionalEvent
}
