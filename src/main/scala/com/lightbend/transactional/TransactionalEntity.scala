package com.lightbend.transactional

import akka.actor.{ActorLogging, Stash}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}
import com.lightbend.transactional.PersistentSagaActorCommands.{CommitTransaction, RollbackTransaction, StartTransaction}
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
  def applyTransactionStarted(started: TransactionStarted)

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
  def inTransaction(currentTransactionId: String): Receive = {
    case CommitTransaction(transactionId, entityId) =>
      persist(TransactionCleared(transactionId, entityId)) { cleared =>
        deliver(context.system.actorSelection(getSagaActorPath(transactionId))) { deliveryId =>
          val event = EventConfirmationSentToSaga(deliveryId, cleared.transactionId, cleared)
          persist(event) { confirm =>
            context.become(awaitSagaCommitConfirmReceipt(confirm, cleared).orElse { case _ => stash })
          }
          event
        }
      }

    case RollbackTransaction(transactionId, entityId) =>
      println("rollback")
      persist(TransactionReversed(transactionId, entityId)) { reversed =>
        deliver(context.system.actorSelection(getSagaActorPath(transactionId))) { deliveryId =>
          val event = EventConfirmationSentToSaga(deliveryId, reversed.transactionId, reversed)
          persist(event) { confirm =>
            context.become(awaitSagaRollbackConfirmReceipt(confirm, reversed).orElse { case _ => stash })
          }
          event
        }
      }

    case StartTransaction(transactionId, _) if transactionId == currentTransactionId =>
      // Ignore any duplicate sends of current transaction.

    case _ => stash()
  }

  /**
    * This must be called after persisting TransactionStarted.
    */
  def onTransactionStartedPersist(started: TransactionStarted): Unit = {
    deliver(context.system.actorSelection(getSagaActorPath(started.transactionId))) { deliveryId =>
      val event = EventConfirmationSentToSaga(deliveryId, started.transactionId, started)
      persist(event) { confirm =>
        context.become(awaitSagaPendingConfirmReceipt(confirm, started).orElse { case _ => stash })
      }
      event
    }
  }

  /**
    * Call this on recover of EventConfirmed.
    */
  def awaitConfirmationReceipt(confirm: EventConfirmationSentToSaga): Unit =
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
      case started: TransactionStarted =>
        applyTransactionStarted(started)
        context.become(inTransaction(receipt.envelope.transactionId).orElse { case _ => stash })
      case cleared: TransactionCleared =>
        applyTransactionCleared(cleared)
        context.become(active)
      case reversed: TransactionReversed =>
        applyTransactionReversed(reversed)
        context.become(active)
    }


  /**
    * In this state we await confirmation of the event received from the saga.
    * The entity will have to pass a transition to in order to progress to an inTransaction state.
    */
  private def awaitSagaPendingConfirmReceipt(eventConfirmed: EventConfirmationSentToSaga, started: TransactionStarted): Receive = {
    case receipt: SagaDeliveryReceipt if receipt.deliveryId == eventConfirmed.deliveryId =>
      persist(EventConfirmedReceipt(receipt, started)) { _ =>
        confirmDelivery(receipt.deliveryId)
        started.event match {
          case ex: TransactionalExceptionEvent =>
            applyTransactionException(ex)
            context.become(active) // No need for this entity to participate any further in the transaction.
          case _ =>
            applyTransactionStarted(started)
            context.become(inTransaction(started.transactionId))
        }
      }
    case _ => stash()
  }

  /**
    * In this state we await confirmation of the commit received from the saga.
    */
  private def awaitSagaCommitConfirmReceipt(eventConfirmed: EventConfirmationSentToSaga, cleared: TransactionCleared): Receive = {
    case receipt: SagaDeliveryReceipt if receipt.deliveryId == eventConfirmed.deliveryId =>
      persist(EventConfirmedReceipt(receipt, cleared)) { _ =>
        applyTransactionCleared(cleared)
        context.become(active)
        unstashAll()
        confirmDelivery(receipt.deliveryId)
      }
    case _ => stash()
  }

  /**
    * In this state we await confirmation of the rollback received from the saga.
    */
  private def awaitSagaRollbackConfirmReceipt(eventConfirmed: EventConfirmationSentToSaga, reversed: TransactionReversed): Receive = {
    case receipt: SagaDeliveryReceipt if receipt.deliveryId == eventConfirmed.deliveryId =>
      persist(EventConfirmedReceipt(receipt, reversed)) { _ =>
        applyTransactionReversed(reversed)
        context.become(active)
        active
        unstashAll()
        confirmDelivery(receipt.deliveryId)
      }
    case _ => stash()
  }

  private def getSagaActorPath(transactionId: String): String =
    s"/user/${PersistentSagaActor.EntityPrefix}$transactionId"
}
