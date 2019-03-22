package com.lightbend.transactional

import com.lightbend.transactional.PersistentSagaActorCommands.TransactionalCommand
import com.lightbend.transactional.lightbend.{EntityId, TransactionId}

/**
  * Wrapping "Envelope" events to be handled by entities participating in a saga.
  */
object PersistentSagaActorEvents {

  /** Events upon a saga **/
  sealed trait PersistentSagaActorEvent
  case class SagaStarted(transactionId: TransactionId, description: String, commands: Seq[TransactionalCommand])
    extends PersistentSagaActorEvent

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
  }

  // Transactional event wrappers.
  case class TransactionStarted(transactionId: TransactionId, entityId: EntityId, event: TransactionalEvent)
    extends TransactionalEventEnvelope
  case class TransactionCleared(transactionId: TransactionId, entityId: EntityId)
    extends TransactionalEventEnvelope
  case class TransactionReversed(transactionId: TransactionId, entityId: EntityId)
    extends TransactionalEventEnvelope

  // Use this event to complete an unsuccessful transaction and put the entity back into ready state.
  case class TransactionComplete(transactionId: TransactionId, entityId: EntityId)
    extends TransactionalEventEnvelope

  // Trait for any entity events participating in a saga.
  trait TransactionalEvent

  // Trait for any entity events participating in a saga that are exceptions.
  trait TransactionalExceptionEvent extends TransactionalEvent
  /** **/

  /**
    * This message should be sent by all participating entities as a side effect of each
    * persist. This is the implementation of "read your writes" for this functionality.
    */
  case class EventConfirmationSentToSaga(deliveryId: Long, transactionId: TransactionId, envelope: TransactionalEventEnvelope)

  /**
    * The confirmations that the saga has seen the above message and is sent to the participating entity.
    */
  case class SagaDeliveryReceipt(deliveryId: Long)

  /**
    * Entities should persist this as opposed to SagaDeliveryReceipt, which will wrap the receipt with the corresponding domain event.
    */
  case class EventConfirmedReceipt(receipt: SagaDeliveryReceipt, envelope: TransactionalEventEnvelope)
}
