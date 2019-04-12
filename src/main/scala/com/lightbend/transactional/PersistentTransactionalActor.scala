package com.lightbend.transactional

import akka.actor.{ActorLogging, ActorSelection, Props, ReceiveTimeout, Timers}
import akka.persistence.PersistentActor
import com.lightbend.transactional.PersistentTransactionCommands.{AddStreamingCommand, CommitTransaction, EndStreamingCommands, RollbackTransaction, StartEntityTransaction, StartTransaction, TransactionalCommand}
import com.lightbend.transactional.PersistentTransactionEvents.TransactionalEventEnvelope

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * Companion object.
  */
object PersistentTransactionalActor {

  final val EntityPrefix = "persistent-transaction-"

  final val RegionName = "persistent-transaction-region"

  /**
    * Use this for asks between transactions and entities.
    */
  case class Ack(correlationId: String)

  /**
    * This would normally be kept private, but due to the complexity here, it's useful for testing.
    */
  case class TransactionState(
    transactionId: String,
    description: String,
    currentState: String,
    streamingComplete: Boolean = false,
    sequenceNum: Long = 0L,
    commands: Seq[TransactionalCommand] = Seq.empty,
    pendingConfirmed: Seq[String] = Seq.empty,
    commitConfirmed: Seq[String] = Seq.empty,
    rollbackConfirmed: Seq[String] = Seq.empty,
    exceptions: Seq[TransactionalEventEnvelope] = Seq.empty)

  case object GetTransactionState

  /**
    * Props factory method.
    */
  def props(retryAfter: FiniteDuration, timeoutAfter: FiniteDuration): Props =
    Props(new PersistentTransactionalActor(retryAfter, timeoutAfter))
}

/**
  * This is effectively a long lived transaction that operates within an Akka cluster. Classic saga patterns
  * will be followed, such as retrying rollback over and over as well as retry of transactions over and over if
  * necessary, before rollback.
  *
  */
class PersistentTransactionalActor(retryAfter: FiniteDuration, timeoutAfter: FiniteDuration)
  extends Timers with PersistentActor with ActorLogging {

  import PersistentTransactionalActor._
  import PersistentTransactionEvents._

  implicit def ec: ExecutionContext = context.system.dispatcher

  override def persistenceId: String = self.path.name

  /**
    * How often to retry transaction subscription confirmations missing after a wait time. The retry-after setting
    * should be coarse enough to allow the same timer to be used across pending, committing and rollingBack.
    */
  private case object Retry

  /**
    * Transaction timeout message. A transaction should not be blocked for very long as the participating entities
    * are also blocked and subject to stash overflow.
    */
  private case object TimeoutTransaction

  private case object RetryTimerKey
  private case object TimeoutTimerKey

  context.setReceiveTimeout(10.seconds)

  final private val Uninitialized = "uninitialized"
  final private val Pending = "pending"
  final private val Committing = "committing"
  final private val RollingBack = "rollingBack"

  private var state: TransactionState = TransactionState("", "", Uninitialized)

  override def receiveCommand: Receive = uninitialized

  /**
    * In this state we are hobbled until we are sent the start message. Instantiation of this actor has to be in two
    * steps since the edge, in this case the restful route, must assign the transactionId, which automatically
    * becomes the persistentId. Since cluster sharding only allows construction with objects known when the app
    * starts, we have to send the commands as a second step.
    */
  private def uninitialized: Receive = {
    case StartTransaction(transactionId, description) =>
      persist(TransactionStarted(transactionId, description)) { started =>
        sender() ! Ack(transactionId)
        applyTransactionStarted(started)
        applyTransactionStartedSideEffects(started)
      }
    case ReceiveTimeout =>
      log.error(s"Aborting transaction ${self.path.name} never received StartTransaction command.")
      context.stop(self)
  }

  /**
    * The pending state. No commit OR rollback will occur until all pending events are in place, as per a Saga.
    * Here we receive event subscription messages applicable to "pending".
    *
    * Used in combination with applyWithPending.
    */
  private def pending: Receive = {
    case started @ EntityTransactionStarted(_, entityId, _) =>
      started.event match {
        case _: TransactionalExceptionEvent =>
          if (!state.exceptions.exists(_.entityId == entityId)) {
            persist(started) { event =>
              sender() ! Ack
              applyEntityTransactionStarted(event)
              applyEntityTransactionStartedSideEffects(started)
            }
          }
        case _ =>
          if (!state.pendingConfirmed.contains(entityId)) {
            persist(started) { event =>
              sender() ! Ack
              applyEntityTransactionStarted(event)
              applyEntityTransactionStartedSideEffects(started)
            }
          }
      }
    case AddStreamingCommand(transactionId, command, sequence) =>
      persist(StreamingCommandAdded(transactionId, command, sequence)) { added =>
        sender() ! Ack(transactionId)

        if (validSequence(sequence)) {
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
        if (validSequence(sequence)) {
          sender() ! Ack(transactionId)
          applyStreamingCommandsEnded(ended)
          pendingTransitionCheckSideEffects()
        }
        else {
          log.info(s"ending transaction $transactionId due to EndStreamingCommands command out of sequence.")
          context.stop(self)
        }
      }
    case Retry =>
      retryPendingSideEffects()
    case GetTransactionState =>
      sender() ! state
  }

  /**
    * The committing state. When in this state we can only repeatedly attempt to commit. This transaction will remain
    * alive until commits have occurred across the board.
    * Here we receive messages from the entities applicable to "committing".
    */
  private def committing: Receive = {
    case cleared@TransactionCleared(transactionId, entityId, _) =>
      if (!state.commitConfirmed.contains(entityId)) {
        persist(cleared) { event =>
          sender() ! Ack(transactionId)
          applyTransactionCleared(event)
          onCommitOrRollbackSideEffects()
        }
      }
    case Retry =>
      retryCommitSideEffects()
    case GetTransactionState =>
      sender() ! state
  }

  /**
    * The rolling back state. When in this state we can only repeatedly attempt to rollback. This transaction will remain
    * alive until rollbacks have occurred across the board.
    * Here we receive event subscription messages applicable to "rollingBack".
    */
  private def rollingBack: Receive = {
    case reversed @ TransactionReversed(transactionId, entityId, _) =>
      if (!state.rollbackConfirmed.contains(entityId)) {
        persist(reversed) { event =>
          sender() ! Ack(transactionId)
          applyTransactionReversed(event)
          onCommitOrRollbackSideEffects()
        }
      }
    case Retry =>
      retryRollbackSideEffects()
    case GetTransactionState =>
      sender() ! state
  }

  private def applyTransactionStarted(started: TransactionStarted): Unit = {
    state = TransactionState(started.transactionId, started.description, Pending, false)
    context.become(pending)
  }

  private def applyTransactionStartedSideEffects(started: TransactionStarted): Unit = {
    log.info(s"starting new transaction with transactionId: ${started.transactionId}")
    context.actorOf(TaggedEventSubscription.props(started.transactionId, self))
    timers.startPeriodicTimer(RetryTimerKey, Retry, retryAfter)
    timers.startPeriodicTimer(TimeoutTimerKey, TimeoutTransaction, timeoutAfter)
  }

  private def applyEntityTransactionStarted(started: EntityTransactionStarted): Unit = {
    started.event match {
      case _: TransactionalExceptionEvent =>
        state = state.copy(exceptions = (state.exceptions :+ started).sortWith(_.entityId < _.entityId))
      case _ =>
        state = state.copy(pendingConfirmed = (state.pendingConfirmed :+ started.entityId).sortWith(_ < _))
    }

    pendingTransitionCheck()
  }

  private def applyEntityTransactionStartedSideEffects(started: EntityTransactionStarted): Unit = {
    started.event match {
      case _: TransactionalExceptionEvent =>
        log.info(s"Transaction rolling back when possible due to exception on account ${started.entityId}.")
      case _ =>
    }

    pendingTransitionCheckSideEffects()
  }

  private def applyStreamingCommandAdded(added: StreamingCommandAdded): Unit =
    state = state.copy(sequenceNum = added.sequence, commands = state.commands :+ added.command)

  private def applyStreamingCommandAddedSideEffects(added: StreamingCommandAdded): Unit =
    getShardRegion(added.command.entityType) ! StartEntityTransaction(added.transactionId, added.command.entityId,
      added.command)

  private def applyStreamingCommandsEnded(ended: StreamingCommandsEnded): Unit = {
    state = state.copy(streamingComplete = true, sequenceNum = ended.sequence)
    pendingTransitionCheck()
  }

  private def applyTransactionCleared(cleared: TransactionCleared): Unit =
    state = state.copy(commitConfirmed = (state.commitConfirmed :+ cleared.entityId).sortWith(_ < _))

  private def pendingTransitionCheck(): Unit =
    if (canCommit()) {
      state = state.copy(currentState = Committing)
      context.become(committing)
    }
    else if (canRollback()) {
      state = state.copy(currentState = RollingBack)
      context.become(rollingBack)
    }

  private def pendingTransitionCheckSideEffects(): Unit =
    if (state.currentState == Committing)
      state.commands.foreach(cmd =>
        getShardRegion(cmd.entityType) ! CommitTransaction(state.transactionId, cmd.entityId)
      )
    else if (state.currentState == RollingBack)
      state.pendingConfirmed.foreach(entityId =>
        getShardRegion(state.commands
          .find(_.entityId == entityId).get.entityType) ! RollbackTransaction(state.transactionId, entityId)
      )

  private def applyTransactionReversed(reversed: TransactionReversed): Unit =
    state = state.copy(rollbackConfirmed = (state.rollbackConfirmed :+ reversed.entityId).sortWith(_ < _))

  private def onCommitOrRollbackSideEffects(): Unit =
    if (canComplete())
      persist(PersistentTransactionComplete(state.transactionId)) ( _ =>
        onPersistentTransactionComplete()
      )

  private def onPersistentTransactionComplete(): Unit = {
    log.info(s"Transaction completed successfully for transactionId: ${state.transactionId}")
    timers.cancelAll()
    context.stop(self)
  }

  private def getShardRegion(regionName: String): ActorSelection =
    context.actorSelection(s"/user/$regionName")

  private def retryPendingSideEffects(): Unit = {
    log.info(s"retrying commands for transactionId: ${state.transactionId}")
    state.commands.diff(state.pendingConfirmed).foreach( c =>
      getShardRegion(c.entityId) ! c
    )
  }

  private def retryCommitSideEffects(): Unit = {
    log.info(s"retrying commit commands for transactionId: ${state.transactionId}")
    state.commands.diff(state.pendingConfirmed).foreach( c =>
      getShardRegion(c.entityId) ! c
    )
  }

  private def retryRollbackSideEffects(): Unit = {
    log.info(s"retrying rollback commands for transactionId: ${state.transactionId}")
    state.commands.diff(state.pendingConfirmed).foreach( c =>
      getShardRegion(c.entityId) ! c
    )
  }

  private def canCommit(): Boolean =
    if (state.streamingComplete && state.pendingConfirmed.size == state.commands.size)
      true
    else
      false

  private def canRollback(): Boolean =
    if (state.exceptions.nonEmpty && state.streamingComplete &&
      state.commands.size == state.pendingConfirmed.size + state.exceptions.size)
      true
    else
      false

  private def canComplete(): Boolean =
    if (state.currentState == Committing && state.commitConfirmed.size == state.commands.size)
      true
    else if (state.currentState == RollingBack
      && state.rollbackConfirmed.size == state.commands.size - state.exceptions.size)
      true
    else
      false

  private def validSequence(sequence: Long): Boolean =
    if (sequence == 0L && state.sequenceNum == 0L)
      true
    else if (sequence - state.sequenceNum == 1)
      true
    else
      false

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
      onPersistentTransactionComplete()
    case added: StreamingCommandAdded =>
      if (validSequence(added.sequence))
        applyStreamingCommandAdded(added)
      else {
        log.info(s"stopping recovery of transaction ${added.transactionId} due to command out of sequence.")
        context.stop(self)
      }
    case ended @ StreamingCommandsEnded(transactionId, sequence) =>
      if (validSequence(sequence))
        applyStreamingCommandsEnded(ended)
      else {
        log.info(s"stopping recovery of transaction $transactionId due to StreamingCommandsEnded command out of sequence.")
        context.stop(self)
      }
  }
}
