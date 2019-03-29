package com.lightbend.transactional

import akka.actor.{ActorLogging, Stash}
import akka.persistence.PersistentActor
import akka.persistence.journal.Tagged
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
    case CommitTransaction(transactionId, entityId, eventTag) =>
      val cleared = TransactionCleared(transactionId, entityId, eventTag)
      persist(Tagged(cleared, Set(eventTag))) { _ =>
        onTransactionCleared(cleared)
      }

    case RollbackTransaction(transactionId, entityId, eventTag) =>
      val reversed = TransactionReversed(transactionId, entityId, eventTag)
      persist(Tagged(reversed, Set(eventTag))) { _ =>
        onTransactionReversed(reversed)
      }

    case StartTransaction(transactionId, _, _, _) if transactionId == currentTransactionId =>
      // Ignore any duplicate sends of current transaction.

    case _ => stash()
  }

  /**
    * Call this on TransactionStarted event persist and recovery.
    */
  def onTransactionStarted(started: TransactionStarted): Unit =
    started.event match {
      case ex: TransactionalExceptionEvent =>
        applyTransactionException(ex)
        context.become(active) // No need for this entity to wait for transaction completion.
      case _ =>
        applyTransactionStarted(started)
        context.become(inTransaction(started.transactionId).orElse { case _ => stash })
    }

  /**
    * Call this on recovery of any transactional event.
    */
  def recover(envelope: TransactionalEventEnvelope): Unit =
    envelope match {
      case started: TransactionStarted =>
        onTransactionStarted(started)
      case cleared: TransactionCleared =>
        onTransactionCleared(cleared)
      case reversed: TransactionReversed =>
        onTransactionReversed(reversed)
    }

  /**
    * Called on TransactionCleared event persist and recovery.
    */
  private def onTransactionCleared(cleared: TransactionCleared): Unit = {
    applyTransactionCleared(cleared)
    context.become(active)
    unstashAll()
  }

  /**
    * Called on TransactionReversed event persist and recovery.
    */
  private def onTransactionReversed(reversed: TransactionReversed): Unit = {
    applyTransactionReversed(reversed)
    context.become(active)
    unstashAll()
  }
}
