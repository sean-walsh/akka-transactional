package com.lightbend.transactional

import akka.actor.{ActorLogging, ActorSelection, Stash}
import akka.persistence.PersistentActor
import com.lightbend.transactional.PersistentSagaActor.Ack
import com.lightbend.transactional.PersistentSagaActorCommands.{CommitTransaction, RollbackTransaction, StartTransaction}
import com.lightbend.transactional.PersistentSagaActorEvents._

/**
  * Mixin for a set of behaviors helpful/necessary to participate in a transactional saga.
  * See /bankaccount example for usage.
  */
trait TransactionalEntity extends PersistentActor with ActorLogging with Stash {

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
        persistentSagaShardRegion ! cleared
        context.become(awaitSagaCommitConfirmReceipt(cleared).orElse { case _ => stash })
      }

    case RollbackTransaction(transactionId, entityId) =>
      persist(TransactionReversed(transactionId, entityId)) { reversed =>
        persistentSagaShardRegion ! reversed
        context.become(awaitSagaRollbackConfirmReceipt(reversed).orElse { case _ => stash })
      }

    case StartTransaction(transactionId, _, _) if transactionId == currentTransactionId =>
      // Ignore any duplicate sends of current transaction.

    case _ => stash()
  }

  /**
    * This must be called after persisting TransactionStarted.
    */
  def onTransactionStartedPersist(started: TransactionStarted): Unit = {
    persistentSagaShardRegion ! started
    context.become(awaitSagaPendingConfirmReceipt(started).orElse { case _ => stash })
  }

  /**
    * Call this on recovery of TransactionStarted.
    */
  def onTransactionStartedRecovery(started: TransactionStarted): Unit =
    context.become(awaitSagaPendingConfirmReceipt(started).orElse { case _ => stash })

  /**
    * Call this on recovery of TransactionStarted.
    */
  def onTransactionClearedRecovery(cleared: TransactionCleared): Unit =
    context.become(awaitSagaCommitConfirmReceipt(cleared).orElse { case _ => stash })

  /**
    * Call this on recovery of TransactionReversed.
    */
  def onTransactionReversedRecovery(reversed: TransactionReversed): Unit =
    context.become(awaitSagaRollbackConfirmReceipt(reversed).orElse { case _ => stash })

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
  private def awaitSagaPendingConfirmReceipt(started: TransactionStarted): Receive = {
    case receipt: SagaDeliveryReceipt =>
      persist(EventConfirmedReceipt(receipt, started)) { _ =>
        sender() ! Ack
        started.event match {
          case ex: TransactionalExceptionEvent =>
            applyTransactionException(ex)
            context.become(active) // No need for this entity to wait for transaction completion.
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
  private def awaitSagaCommitConfirmReceipt(cleared: TransactionCleared): Receive = {
    case receipt: SagaDeliveryReceipt =>
      persist(EventConfirmedReceipt(receipt, cleared)) { _ =>
        sender() ! Ack
        applyTransactionCleared(cleared)
        context.become(active)
        unstashAll()
      }
    case _ => stash()
  }

  /**
    * In this state we await confirmation of the rollback received from the saga.
    */
  private def awaitSagaRollbackConfirmReceipt(reversed: TransactionReversed): Receive = {
    case receipt: SagaDeliveryReceipt =>
      persist(EventConfirmedReceipt(receipt, reversed)) { _ =>
        sender() ! Ack
        applyTransactionReversed(reversed)
        context.become(active)
        unstashAll()
      }
    case _ => stash()
  }

  /**
    * The shard region for sagas.
    */
  private def persistentSagaShardRegion: ActorSelection =
    context.actorSelection("/user/persistent-saga-region")
}
