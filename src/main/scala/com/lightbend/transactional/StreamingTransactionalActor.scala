package com.lightbend.transactional

import akka.actor.{Props, ReceiveTimeout}
import akka.persistence.RecoveryCompleted
import com.lightbend.transactional.PersistentTransactionCommands.{PersistentTransactionCommand, StartEntityTransaction, StartTransaction, TransactionalCommand}
import com.lightbend.transactional.PersistentTransactionEvents.{EntityTransactionStarted, PersistentTransactionEvent, PersistentTransactionComplete, TransactionCleared, TransactionReversed, TransactionStarted}
import com.lightbend.transactional.PersistentTransactionalActor.{Ack, TransactionState}

import scala.collection.immutable

object StreamingTransactionalActor {
  case class AddStreamingCommand(transactionId: String, command: TransactionalCommand, sequence: Long)
    extends PersistentTransactionCommand
  case class StartStreamingTransaction(transactionId: String, description: String, add: AddStreamingCommand)
    extends StartTransaction
  case class EndStreamingCommands(transactionId: String, sequence: Long) extends PersistentTransactionCommand

  case class StreamingTransactionStarted(transactionId: String, description: String, nodeEventTag: String)
    extends PersistentTransactionEvent {
    override val entityId = transactionId
  }
  case class StreamingCommandAdded(transactionId: String, command: TransactionalCommand, sequence: Long)
    extends PersistentTransactionEvent {
    override val entityId = transactionId
  }
  case class StreamingCommandsEnded(transactionId: String, sequence: Long) extends PersistentTransactionEvent {
    override val entityId = transactionId
  }

  case class StreamingTransactionState(
    streamingComplete: Boolean = false,
    sequenceNum: Long = 0L
  ) extends TransactionState

  /**
    * Props factory method.
    */
  def props(nodeEventTag: String): Props =
    Props(new StreamingTransactionalActor(nodeEventTag))
}

/**
  * Streaming implementation of PersistentTransactionalActor.
  */
class StreamingTransactionalActor(nodeEventTag: String) extends PersistentTransactionalActor(nodeEventTag) {

  import StreamingTransactionalActor._

  override protected var additionalTransactionState: Option[TransactionState] = Some(StreamingTransactionState())

  override protected def uninitialized: Receive = {
    case StartStreamingTransaction(transactionId, description, add) =>
      persistAll(immutable.Seq(
        TransactionStarted(transactionId, description, nodeEventTag, Nil),
        StreamingCommandAdded(transactionId, add.command, add.sequence))) {
          case started: TransactionStarted =>
            sender() ! Ack
            applyTransactionStarted(started)
            applyTransactionStartedSideEffects(started)
          case added: StreamingCommandAdded =>
            applyStreamingCommandAdded(added)
            applyStreamingCommandAddedSideEffects(added)
      }
    case ReceiveTimeout =>
      log.error(s"Aborting transaction ${self.path.name} never received StartTransaction command.")
      context.stop(self)
  }

  override protected def applyWithPending: Receive = {
    case AddStreamingCommand(transactionId, command, sequence) =>
      persist(StreamingCommandAdded(transactionId, command, sequence)) { added =>
        sender() ! Ack

        if (sequence - additionalTransactionState.get.asInstanceOf[StreamingTransactionState].sequenceNum == 1) {
          applyStreamingCommandAdded(added)
          applyStreamingCommandAddedSideEffects(added)
        }
        else {
          log.info(s"ending transaction $transactionId due to command out of sequence.")
          context.stop(self)
        }
      }
    case EndStreamingCommands(transactionId, sequence) =>
      persist(StreamingCommandsEnded(transactionId, sequence)) { ended =>
        if (sequence - additionalTransactionState.get.asInstanceOf[StreamingTransactionState].sequenceNum == 1) {
          sender() ! Ack
          applyStreamingCommandsEnded(ended)
          transitionCheckSideEffects()
        }
        else {
          log.info(s"ending transaction $transactionId due to EndStreamingCommands command out of sequence.")
          context.stop(self)
        }
      }
  }

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

  override protected def commitCondition(): Boolean =
    if (additionalTransactionState.get.asInstanceOf[StreamingTransactionState].streamingComplete &&
      getBasicTransactionState().pendingConfirmed.size == getBasicTransactionState().commands.size)
      true
    else
      false

  override protected def rollbackCondition(): Boolean =
    if (getBasicTransactionState().exceptions.nonEmpty && additionalTransactionState.get
      .asInstanceOf[StreamingTransactionState].streamingComplete && getBasicTransactionState()
      .commands.size == getBasicTransactionState().pendingConfirmed.size + getBasicTransactionState().exceptions.size)
      true
    else
      false

  private def applyStreamingCommandAdded(added: StreamingCommandAdded): Unit = {
    additionalTransactionState = Some(additionalTransactionState.get.asInstanceOf[StreamingTransactionState]
      .copy(sequenceNum = added.sequence))
    onCommandAdded(added.command)
  }

  private def applyStreamingCommandAddedSideEffects(added: StreamingCommandAdded): Unit = {
    getShardRegion(added.command.shardRegion) ! StartEntityTransaction(added.transactionId, added.command.entityId,
      getBasicTransactionState().originalEventTag, added.command)
  }

  private def applyStreamingCommandsEnded(ended: StreamingCommandsEnded): Unit = {
    additionalTransactionState = Some(additionalTransactionState.get.asInstanceOf[StreamingTransactionState]
      .copy(streamingComplete = true))
    transitionCheck()
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
    case added: StreamingCommandAdded =>
      val currentSequence = additionalTransactionState.get.asInstanceOf[StreamingTransactionState].sequenceNum
      if (added.sequence - currentSequence == 1 || currentSequence == 0) {
        applyStreamingCommandAdded(added)
      }
      else {
        log.info(s"stopping recovery of transaction ${added.transactionId} due to command out of sequence.")
        context.stop(self)
      }
    case ended: StreamingCommandsEnded =>
      if (ended.sequence - additionalTransactionState.get.asInstanceOf[StreamingTransactionState].sequenceNum == 1)
        applyStreamingCommandsEnded(ended)
      else {
        log.info(s"stopping recovery of transaction ${ended.transactionId} due to StreamingCommandsEnded command out of sequence.")
        context.stop(self)
      }
    case RecoveryCompleted =>
      if (List(Pending, Committing, RollingBack).contains(getBasicTransactionState().currentState))
        conditionallySpinUpEventSubscriber(getBasicTransactionState().originalEventTag)
  }
}
