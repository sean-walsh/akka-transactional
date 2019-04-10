package com.lightbend.transactional

import akka.actor.{ActorLogging, ActorNotFound, ActorSelection, Timers}
import akka.persistence.PersistentActor
import akka.util.Timeout
import com.lightbend.transactional.PersistentTransactionCommands.{CommitTransaction, RollbackTransaction, TransactionalCommand}
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
  case class Ack()

  /**
    * This would normally be kept private, but due to the complexity here, it's useful for testing.
    */
  trait TransactionState
  case class BaseTransactionState(
    transactionId: String,
    description: String,
    currentState: String,
    originalEventTag: String,
    commands: Seq[TransactionalCommand] = Seq.empty,
    pendingConfirmed: Seq[String] = Seq.empty,
    commitConfirmed: Seq[String] = Seq.empty,
    rollbackConfirmed: Seq[String] = Seq.empty,
    exceptions: Seq[TransactionalEventEnvelope] = Seq.empty) extends TransactionState

  case object GetTransactionState
}

/**
  * This is effectively a long lived transaction that operates within an Akka cluster. Classic saga patterns
  * will be followed, such as retrying rollback over and over as well as retry of transactions over and over if
  * necessary, before rollback.
  *
  * All persisted transactions will be tagged with the unique-per-node nodeEventTag.
  */
abstract class PersistentTransactionalActor(nodeEventTag: String) extends Timers with PersistentActor with ActorLogging {

  import PersistentTransactionalActor._
  import PersistentTransactionEvents._

  implicit def ec: ExecutionContext = context.system.dispatcher

  override def persistenceId: String = self.path.name

  /**
    * How often to retry transaction subscription confirmations missing after a wait time. The retry-after setting
    * should be coarse enough to allow the same timer to be used across pending, committing and rollingBack.
    */
  private case object Retry

  private case object TimerKey

  /**
    * Set this according to implementation.
    */
  protected def retryAfter: FiniteDuration

  context.setReceiveTimeout(10.seconds)

  final protected val Uninitialized = "uninitialized"
  final protected val Pending = "pending"
  final protected val Committing = "committing"
  final protected val RollingBack = "rollingBack"

  private var state: BaseTransactionState = BaseTransactionState("", "", Uninitialized, "")
  protected def getBasicTransactionState(): BaseTransactionState = state.copy()

  override def receiveCommand: Receive = uninitialized

  /**
    * In this state we are hobbled until we are sent the start message. Instantiation of this actor has to be in two
    * steps since the edge, in this case the restful route, must assign the transactionId, which automatically
    * becomes the persistentId. Since cluster sharding only allows construction with objects known when the app
    * starts, we have to send the commands as a second step.
    *
    * Here, the start of the transaction, ReceiveTimeoutshould be handled.
    */
  protected def uninitialized: Receive

  /**
    * Override this for additional "pending" state cases per implementation.
    */
  protected def applyWithPending: Receive = {
    case _ => unhandled(_)
  }

  /**
    * This should be called from uninitialized state to transition into pending.
    */
  protected def applyTransactionStartedSideEffects(started: TransactionStarted): Unit = {
    log.info(s"starting new transaction with transactionId: ${started.transactionId}")
    conditionallySpinUpEventSubscriber(state.originalEventTag)
    timers.startPeriodicTimer(TimerKey, Retry, retryAfter)
    postTransactionStartedSideEffects(started)
  }

  /**
    * Override for additional TransactionStartedSideEffects.
    */
  protected def postTransactionStartedSideEffects(started: TransactionStarted): Unit = {}

  /**
    * Override this if necessary to determine if it's time to commit.
    */
  protected def commitCondition(): Boolean =
    if (state.pendingConfirmed.size == state.commands.size)
      true
    else
      false

  /**
    * Ensure this is called on recovery.
    */
  protected def applyTransactionCleared(cleared: TransactionCleared): Unit =
    state = state.copy(commitConfirmed = (state.commitConfirmed :+ cleared.entityId).sortWith(_ < _))

  protected def onTransactionClearedSideEffects(): Unit =
    if (completionCondition()) {
      persist(PersistentTransactionComplete(state.transactionId)) { _ =>
        log.info(s"Transaction completed successfully for transactionId: ${state.transactionId}")
        timers.cancel(TimerKey)
        context.stop(self)
      }
    }

  /**
    * Ensure this is called on recovery.
    */
  protected def applyTransactionReversed(event: TransactionReversed): Unit = {
    state = state.copy(rollbackConfirmed = (state.rollbackConfirmed :+ event.entityId).sortWith(_ < _))

    if (completionCondition()) {
      persist(PersistentTransactionComplete(state.transactionId)) { _ =>
        log.info(s"Transaction completed with rollback for transactionId: ${state.transactionId}")
        timers.cancel(TimerKey)
        context.stop(self)
      }
    }
  }

  /**
    * Derive entity's shard region ActorSelection.
    */
  protected def getShardRegion(regionName: String): ActorSelection =
    context.actorSelection(s"/user/$regionName")

  /**
    * This must be implemented.
    */
  protected def retryPendingSideEffects(): Unit

  /**
    * This must be implemented.
    */
  protected def retryCommittingSideEffects(): Unit

  /**
    * Holder of any additional state per implementation.
    */
  protected var additionalTransactionState: Option[TransactionState]

  /**
    * Call this when a command is added to the transaction. Use with streaming implementations.
    */
  protected def onCommandAdded(command: TransactionalCommand): Unit = {
    state = state.copy(commands = state.commands :+ command)
  }

  /**
    * The pending state. No commit OR rollback will occur until all pending events are in place, as per a Saga.
    * Here we receive event subscription messages applicable to "pending".
    *
    * Used in combination with applyWithPending.
    */
  private def pending: Receive = {
    case started @ EntityTransactionStarted(_, entityId, _, _) =>
      started.event match {
        case _: TransactionalExceptionEvent =>
          if (!state.exceptions.exists(_.entityId == entityId)) {
            persist(started) { event =>
              sender() ! Ack
              applyEntityTransactionStarted(event)
              applyEntityTransactionStartedSideEffects(started)
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
    case Retry =>
      retryPendingSideEffects()
    case GetTransactionState =>
      sender() ! (state, additionalTransactionState)
  }

  /**
    * The committing state. When in this state we can only repeatedly attempt to commit. This transaction will remain
    * alive until commits have occurred across the board.
    * Here we receive messages from the entities applicable to "committing".
    */
  private def committing: Receive = {
    case cleared@TransactionCleared(_, entityId, _) =>
      if (!state.commitConfirmed.contains(entityId)) {
        persist(cleared) { event =>
          sender() ! Ack
          applyTransactionCleared(event)
          onTransactionClearedSideEffects()
        }
      }
    case Retry =>
      retryCommittingSideEffects()
    case GetTransactionState =>
      sender() ! state
  }

  /**
    * The rolling back state. When in this state we can only repeatedly attempt to rollback. This transaction will remain
    * alive until rollbacks have occurred across the board.
    * Here we receive event subscription messages applicable to "rollingBack".
    */
  private def rollingBack: Receive = {
    case reversed@TransactionReversed(_, entityId, _) =>
      if (!state.rollbackConfirmed.contains(entityId)) {
        persist(reversed) { event =>
          sender() ! Ack
          applyTransactionReversed(event)
        }
      }
    case GetTransactionState =>
      sender() ! state
  }

  protected def applyTransactionStarted(started: TransactionStarted): Unit = {
    state = BaseTransactionState(started.transactionId, started.description, Pending, started.nodeEventTag, started.commands)
    context.become(pending.orElse(applyWithPending))
  }

  /**
    * Ensure this is called on recover.
    */
  protected def applyEntityTransactionStarted(started: EntityTransactionStarted): Unit = {
    started.event match {
      case _: TransactionalExceptionEvent =>
        state = state.copy(exceptions = (state.exceptions :+ started).sortWith(_.entityId < _.entityId))
      case _ =>
        state = state.copy(pendingConfirmed = (state.pendingConfirmed :+ started.entityId).sortWith(_ < _))
    }

    pendingTransitionCheck()
  }

  protected def pendingTransitionCheck(): Unit =
    if (commitCondition()) {
      state = state.copy(currentState = Committing)
      context.become(committing)
    }
    else if (rollbackCondition()) {
      state = state.copy(currentState = RollingBack)
      context.become(rollingBack)
    }

  protected def pendingTransitionCheckSideEffects(): Unit =
    if (getBasicTransactionState().currentState == Committing)
      getBasicTransactionState().commands.foreach(cmd =>
        getShardRegion(cmd.shardRegion) ! CommitTransaction(getBasicTransactionState().transactionId, cmd.entityId,
          getBasicTransactionState().originalEventTag)
      )
    else if (getBasicTransactionState().currentState == RollingBack)
      getBasicTransactionState().pendingConfirmed.foreach(entityId =>
        getShardRegion(getBasicTransactionState().commands
          .find(_.entityId == entityId).get.shardRegion) ! RollbackTransaction(
          getBasicTransactionState().transactionId, entityId, getBasicTransactionState().originalEventTag)
      )

  /**
    * In the case that this transaction has restarted on or been moved to another node, will ensure that there
    * is an event subscriber for the original eventTag.
    *
    * Ensure this is called on RecoveryComplete.
    */
  protected def conditionallySpinUpEventSubscriber(originalEventTag: String): Unit = {
    if (originalEventTag != nodeEventTag) {
      // Spin up my own event subscriber, unless one already exists.
      implicit val timeout = Timeout(10.seconds)

      val keepAliveDuration: FiniteDuration = context.system.settings.config
        .getDuration("akka-transactional.transient-event-subscription-timeout").toNanos.nanos

      context.actorSelection(s"${TaggedEventSubscription.ActorNamePrefix}/$originalEventTag")
        .resolveOne().recover {
        case ActorNotFound(_) => context.system.actorOf(TransientTaggedEventSubscription
          .props(nodeEventTag, keepAliveDuration),s"${TaggedEventSubscription.ActorNamePrefix}/$nodeEventTag")
      }
    }
  }

  /**
    * Override this if necessary.
    */
  protected def rollbackCondition(): Boolean =
    if (state.exceptions.nonEmpty && state.commands.size == state.pendingConfirmed.size + state.exceptions.size)
      true
    else
      false

  private def applyEntityTransactionStartedSideEffects(started: EntityTransactionStarted): Unit = {
    started.event match {
      case _: TransactionalExceptionEvent =>
        log.info(s"Transaction rolling back when possible due to exception on account ${started.entityId}.")
      case _ =>
    }

    pendingTransitionCheckSideEffects()
  }

  // Checks for completion condition.
  private def completionCondition(): Boolean = {
    if (state.currentState == Committing && state.commitConfirmed.size == state.commands.size)
      true
    else if (state.currentState == RollingBack && state.rollbackConfirmed.size == state.commands.size - state.exceptions.size)
      true
    else
      false
  }
}
