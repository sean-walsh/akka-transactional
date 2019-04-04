package com.lightbend.transactional

import com.lightbend.transactional.PersistentTransactionCommands.{StartEntityTransaction, StartTransaction, TransactionalCommand}
import com.lightbend.transactional.PersistentTransactionalActor.{Ack, GetTransactionState, TransactionState}
import akka.actor.{Props, ReceiveTimeout}
import akka.persistence.RecoveryCompleted
import com.lightbend.transactional.PersistentTransactionEvents._

object BatchingTransactionalActor {
  case class StartBatchingTransaction(transactionId: String, description: String, commands: Seq[TransactionalCommand])
    extends StartTransaction

  /**
    * Props factory method.
    */
  def props(nodeEventTag: String): Props =
    Props(new BatchingTransactionalActor(nodeEventTag))
}

/**
  * Batched implementation of PersistentTransactionalActor.
  */
class BatchingTransactionalActor(nodeEventTag: String) extends PersistentTransactionalActor(nodeEventTag) {

  import BatchingTransactionalActor._

  override protected var additionalTransactionState: Option[TransactionState] = None

  override protected def uninitialized: Receive = {
    case StartBatchingTransaction(transactionId, description, commands) =>
      persist(TransactionStarted(transactionId, description, nodeEventTag, commands)) { started =>
        applyTransactionStarted(started)
        applyTransactionStartedSideEffects(started)
        sender() ! Ack
      }
    case ReceiveTimeout =>
      log.error(s"Aborting transaction ${self.path.name} never received StartTransaction command.")
      context.stop(self)
    case GetTransactionState =>
      sender() ! (getBasicTransactionState(), null)
  }

  override protected def postTransactionStartedSideEffects(started: TransactionStarted): Unit =
    started.commands.foreach(cmd =>
      getShardRegion(cmd.shardRegion) ! StartEntityTransaction(getBasicTransactionState().transactionId, cmd.entityId,
        getBasicTransactionState().originalEventTag, cmd)
    )

  override protected def retryPendingSideEffects(): Unit = {
    log.info(s"retrying commands for transactionId: ${getBasicTransactionState().transactionId}")
    getBasicTransactionState().commands.diff(getBasicTransactionState().pendingConfirmed).foreach( c =>
      getShardRegion(c.entityId) ! c
    )
  }

  override protected def retryCommittingSideEffects(): Unit = {
    log.info(s"retrying commands for transactionId: ${getBasicTransactionState().transactionId}")
    getBasicTransactionState().commands.diff(getBasicTransactionState().pendingConfirmed).foreach( c =>
      getShardRegion(c.entityId) ! c
    )
  }

  final override def receiveRecover: Receive = {
    case started: TransactionStarted =>
      applyTransactionStarted(started)
    case started: EntityTransactionStarted =>
      applyEntityTransactionStarted(started)
    case cleared: TransactionCleared =>
      applyTransactionCleared(cleared)
    case reversed: TransactionReversed =>
      applyTransactionReversed(reversed)
    case _: PersistentTransactionComplete =>
      context.stop(self)
    case RecoveryCompleted =>
      if (List(Pending, Committing, RollingBack).contains(getBasicTransactionState().currentState))
        conditionallySpinUpEventSubscriber(getBasicTransactionState().originalEventTag)
  }
}
