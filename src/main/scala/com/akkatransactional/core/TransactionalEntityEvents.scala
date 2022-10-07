package com.akkatransactional.core

import com.akkatransactional.core.TransactionalEntityCommands.EntityTransactionalCommand

object TransactionalEntityEvents {
  trait TransactionEvent {
    def transactionId: String
  }

  /**
    * Events on behalf of the overall transaction.
    */
  final case class TransactionStarted(transactionId: String, description: String) extends TransactionEvent
  final case class CommandAdded(transactionId: String, entityId: String, entityType: String,
                                command: EntityTransactionalCommand, sequence: Int) extends TransactionEvent
  final case class CommandsEnded(transactionId: String, sequence: Int) extends TransactionEvent
  final case class EntityEventConfirmed(transactionId: String, entityEvent: EntityEvent) extends TransactionEvent
  final case class EntityErrorReported(transactionId: String, entityEvent: EntityEvent) extends TransactionEvent
  final case class EntityClearingConfirmed(transactionId: String, entityEvent: EntityEvent) extends TransactionEvent
  final case class EntityReversalConfirmed(transactionId: String, entityEvent: EntityEvent) extends TransactionEvent
  final case class TransactionError(transactionId: String) extends TransactionEvent
  //final case class TransactionComplete(transactionId: String) extends TransactionEvent




  /**
    * Events on behalf of participating entities.
    */
  trait EntityEvent {
    def transactionId: String

    def entityType: String

    def entityId: String
  }

  trait EntityPendingEvent extends EntityEvent

  trait EntityErrorEvent extends EntityEvent

  trait EntityClearingEvent extends EntityEvent

  trait EntityReversalEvent extends EntityEvent
}
