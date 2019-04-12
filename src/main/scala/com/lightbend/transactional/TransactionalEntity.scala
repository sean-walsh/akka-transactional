package com.lightbend.transactional

import akka.actor.{ActorLogging, Stash}
import akka.persistence.PersistentActor
import akka.persistence.journal.Tagged
import com.lightbend.transactional.PersistentTransactionCommands.{CommitTransaction, RollbackTransaction, StartEntityTransaction}
import com.lightbend.transactional.PersistentTransactionEvents._

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
  def applyTransactionStarted(started: EntityTransactionStarted)

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
    */
  def inTransaction(currentTransactionId: String, event: TransactionalEvent): Receive = {
    case CommitTransaction(transactionId, entityId) =>
      val cleared = TransactionCleared(transactionId, entityId, event)
      persist(Tagged(cleared, Set(transactionId))) { _ =>
        onTransactionCleared(cleared)
      }

    case RollbackTransaction(transactionId, entityId) =>
      val reversed = TransactionReversed(transactionId, entityId, event)
      persist(Tagged(reversed, Set(transactionId))) { _ =>
        onTransactionReversed(reversed)
      }

    case StartEntityTransaction(`currentTransactionId`, _, _) =>
      // Ignore any duplicate sends of current transaction.

    case _ => stash()
  }

  /**
    * Call this on TransactionStarted event persist and recovery.
    */
  def onTransactionStarted(started: EntityTransactionStarted): Unit =
    started.event match {
      case ex: TransactionalExceptionEvent =>
        applyTransactionException(ex)
        context.become(active) // No need for this entity to wait for transaction completion.
      case _ =>
        applyTransactionStarted(started)
        context.become(inTransaction(started.transactionId, started.event).orElse { case _ => stash })
    }

  /**
    * Call this on recovery of any transactional event.
    */
  def recover(envelope: TransactionalEventEnvelope): Unit =
    envelope match {
      case started: EntityTransactionStarted =>
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
