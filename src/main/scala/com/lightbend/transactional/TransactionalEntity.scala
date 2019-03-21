package com.lightbend.transactional

import akka.actor.{ActorLogging, Stash}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}
import com.lightbend.transactional.PersistentSagaActor.Ack
import com.lightbend.transactional.PersistentSagaActorCommands.{CommitTransaction, RollbackTransaction}
import com.lightbend.transactional.PersistentSagaActorEvents._

/**
  * Mixin for a set of behaviors helpful/necessary to participate in a transactional saga.
  * See /bankaccount example for usage.
  */
trait TransactionalEntity extends PersistentActor with ActorLogging with Stash with AtLeastOnceDelivery {

  /**
    * This is the ready for business receive. Here you handle StartTransaction.
    */
  def active: Receive

  /**
    * Implement this to change state upon persist of TransactionStarted.
    */
  def applyTransactionStarted(cleared: TransactionStarted)

  /**
    * Implement this to change state upon persist of transaction commit.
    */
  def applyTransactionCleared(cleared: TransactionCleared)

  /**
    * Implement this to change state upon persist of transaction rollback.
    */
  def applyTransactionReversed(reversed: TransactionReversed)

  /**
    * Implement this to change state upon persist of exception event.
    */
  def applyTransactionException(exception: TransactionalExceptionEvent)

  /**
    * When in a transaction I can only handle commits and rollbacks.
    * @param processing TransactionalEvent the event that was the start of this transaction.
    */
  def inTransaction: Receive = {
    case CommitTransaction(transactionId, entityId) =>
      persist(TransactionCleared(transactionId, entityId)) { cleared =>
        deliver(context.system.actorSelection(s"${PersistentSagaActor.EntityPrefix}${cleared.transactionId}")) { deliveryId =>
          persist(EventConfirmed(deliveryId, cleared.transactionId, cleared)) { confirm =>
            context.become(awaitSagaCommitConfirmReceipt(confirm, cleared).orElse { case _ => stash })
          }
        }
      }

    case RollbackTransaction(transactionId, entityId) =>
      persist(TransactionReversed(transactionId, entityId)) { reversed =>
        deliver(context.system.actorSelection(s"${PersistentSagaActor.EntityPrefix}${reversed.transactionId}")) { deliveryId =>
          persist(EventConfirmed(deliveryId, reversed.transactionId, reversed)) { confirm =>
            context.become(awaitSagaRollbackConfirmReceipt(confirm, reversed).orElse { case _ => stash })
          }
        }
      }
  }

  /**
    * This must be called after persisting TransactionStarted.
    */
  def onTransactionStartedPersist(started: TransactionStarted): Unit = {
    sender() ! Ack // To satisfy Ask from saga.
    deliver(context.system.actorSelection(s"${PersistentSagaActor.EntityPrefix}${started.transactionId}")) { deliveryId =>
      persist(EventConfirmed(deliveryId, started.transactionId, started)) { confirm =>
        context.become(awaitSagaPendingConfirmReceipt(confirm, started).orElse { case _ => stash })
      }
    }
  }

  /**
    * Call this on recover of EventConfirmed.
    */
  def awaitConfirmationReceipt(confirm: EventConfirmed): Unit =
    confirm.envelope match {
      case started: TransactionStarted =>
        context.become(awaitSagaPendingConfirmReceipt(confirm, started).orElse { case _ => stash })
      case cleared: TransactionCleared =>
        context.become(awaitSagaCommitConfirmReceipt(confirm, cleared).orElse { case _ => stash })
      case reversed: TransactionReversed =>
        context.become(awaitSagaRollbackConfirmReceipt(confirm, reversed).orElse { case _ => stash })
    }

  /**
    * Call this on recover of EventConfirmedReceipt.
    */
  def onEventConfirmedReceiptRecovery(receipt: EventConfirmedReceipt): Unit =
    receipt.envelope match {
      case _: TransactionStarted =>
        context.become(inTransaction.orElse { case _ => stash })
      case _: TransactionCleared =>
        context.become(active)
      case _: TransactionReversed =>
        context.become(active)
    }


  /**
    * In this state we await confirmation of the event received from the saga.
    * The entity will have to pass a transition to in order to progress to an inTransaction state.
    */
  private def awaitSagaPendingConfirmReceipt(eventConfirmed: EventConfirmed, started: TransactionStarted): Receive = {
    case receipt: SagaDeliveryReceipt if receipt.deliveryId == eventConfirmed.deliveryId =>
      persist(EventConfirmedReceipt(receipt, started)) { _ =>
        confirmDelivery(receipt.deliveryId)
        started.event match {
          case started: TransactionStarted =>
            started.event match {
              case ex: TransactionalExceptionEvent =>
                applyTransactionException(ex)
                context.become(active) // No need for this entity to participate any further in the transaction.
              case _ =>
                context.become(inTransaction)
            }
        }
      }
  }

  /**
    * In this state we await confirmation of the commit received from the saga.
    */
  private def awaitSagaCommitConfirmReceipt(eventConfirmed: EventConfirmed, cleared: TransactionCleared): Receive = {
    case receipt: SagaDeliveryReceipt if receipt.deliveryId == eventConfirmed.deliveryId =>
      persist(EventConfirmedReceipt(receipt, cleared)) { _ =>
        confirmDelivery(receipt.deliveryId)
        context.become(active)
        unstashAll()
      }
  }

  /**
    * In this state we await confirmation of the rollback received from the saga.
    */
  private def awaitSagaRollbackConfirmReceipt(eventConfirmed: EventConfirmed, reversed: TransactionReversed): Receive = {
    case receipt: SagaDeliveryReceipt if receipt.deliveryId == eventConfirmed.deliveryId =>
      persist(EventConfirmedReceipt(receipt, reversed)) { _ =>
        confirmDelivery(receipt.deliveryId)
        active
        unstashAll()
      }
  }
}
