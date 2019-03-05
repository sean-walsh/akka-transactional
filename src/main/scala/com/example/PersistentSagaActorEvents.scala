package com.example

import com.example.PersistentSagaActorCommands.TransactionalCommand

/**
  * Wrapping "Envelope" events to be handled by entities participating in a saga.
  */
object PersistentSagaActorEvents {

  /** Events upon a saga **/

  sealed trait PersistentSagaActorEvent
  case class SagaStarted(transactionId: TransactionId, description: String, commands: Seq[TransactionalCommand],
                         originalEventTag: EventTag) extends PersistentSagaActorEvent

  case class SagaPendingConfirmed(transactionId: TransactionId, entityId: EntityId) extends PersistentSagaActorEvent
  case class SagaExceptionConfirmed(transactionId: TransactionId, envelope: TransactionalEventEnvelope)
    extends PersistentSagaActorEvent
  case class SagaCommitConfirmed(transactionId: TransactionId, entityId: EntityId) extends PersistentSagaActorEvent
  case class SagaRollbackConfirmed(transactionId: TransactionId, entityId: EntityId) extends PersistentSagaActorEvent

  /** Events from entities **/

  // Envelope to wrap events.
  trait TransactionalEventEnvelope {
    def transactionId: TransactionId
    def entityId: EntityId
    def event: TransactionalEvent
  }

  // Transactional event wrappers.
  case class TransactionStarted(transactionId: TransactionId, entityId: EntityId, event: TransactionalEvent)
    extends TransactionalEventEnvelope
  case class TransactionCleared(transactionId: TransactionId, entityId: EntityId, event: TransactionalEvent)
    extends TransactionalEventEnvelope
  case class TransactionReversed(transactionId: TransactionId, entityId: EntityId, event: TransactionalEvent)
    extends TransactionalEventEnvelope

  // Use this event to complete an unsuccessful transaction and put the entity back into ready state.
  case class TransactionComplete(transactionId: TransactionId, entityId: EntityId, event: TransactionalEvent)
    extends TransactionalEventEnvelope

  // Trait for any entity events participating in a saga.
  trait TransactionalEvent

  // Trait for any entity events participating in a saga that are exceptions.
  trait TransactionalExceptionEvent extends TransactionalEvent
}
