package com.lightbend.transactional

import com.lightbend.transactional.PersistentSagaActorCommands.TransactionalCommand
import com.lightbend.transactional.lightbend.{EntityId, TransactionId}

/**
  * Wrapping "Envelope" events to be handled by entities participating in a saga.
  */
object PersistentSagaActorEvents {

  /** Events upon a saga **/
  case class SagaStarted(transactionId: TransactionId, description: String, nodeEventTag: String,
                         commands: Seq[TransactionalCommand])

  /** Events from entities **/
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
