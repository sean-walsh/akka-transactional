package com.lightbend.transactional

import com.lightbend.transactional.PersistentSagaActorCommands.TransactionalCommand
import com.lightbend.transactional.lightbend.{EntityId, TransactionId}

/**
  * Wrapping "Envelope" events to be handled by entities participating in a saga.
  */
object PersistentSagaActorEvents {

  /** Events upon a saga **/
  case class SagaStarted(transactionId: TransactionId, description: String, commands: Seq[TransactionalCommand])

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
    * The confirmations that the saga has seen the above message and is sent to the participating entity.
    */
  case class SagaDeliveryReceipt(entityId: EntityId)

  /**
    * Entities should persist this as opposed to SagaDeliveryReceipt, which will wrap the receipt with the corresponding domain event.
    */
  case class EventConfirmedReceipt(receipt: SagaDeliveryReceipt, envelope: TransactionalEventEnvelope)
}
