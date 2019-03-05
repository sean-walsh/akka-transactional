package com.lightbend.transactional

import akka.actor.{ActorLogging, ActorNotFound, ActorRef, Props, ReceiveTimeout, Timers}
import akka.persistence.{PersistentActor, RecoveryCompleted}
import akka.util.Timeout
import com.lightbend.transactional.PersistentSagaActorCommands.TransactionalCommand
import com.lightbend.transactional.PersistentSagaActorEvents.TransactionalEventEnvelope
import com.lightbend.transactional.lightbend.{EventTag, PersistenceId}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * Companion object.
  */
object PersistentSagaActor {

  val EntityPrefix = "persistent-saga-actor-"

  // States of a saga
  object SagaStates  {
    val Uninitialized = "uninitialized"
    val Pending = "pending"
    val Committing = "committing"
    val RollingBack = "rollingBack"
    val Complete = "complete"
  }

  import SagaStates._

  case class SagaState(
    transactionId: String,
    originalEventTag: EventTag,
    description: String,
    currentState: String = Uninitialized,
    commands: Seq[TransactionalCommand] = Seq.empty,
    pendingConfirmed: Seq[PersistenceId] = Seq.empty,
    commitConfirmed: Seq[PersistenceId] = Seq.empty,
    rollbackConfirmed: Seq[PersistenceId] = Seq.empty,
    exceptions: Seq[TransactionalEventEnvelope] = Seq.empty)

  /**
    * Props factory method.
    */
  def props(persistentEntityRegion: ActorRef, nodeEventTag: EventTag): Props =
    Props(new PersistentSagaActor(persistentEntityRegion, nodeEventTag))
}

/**
  * This is effectively a long lived transaction that operates within an Akka cluster. Classic saga patterns
  * will be followed, such as retrying rollback over and over as well as retry of transactions over and over if
  * necessary, before rollback.
  * --Retry is exhaustive in that all events will be resubscribed and ignored if already processed.
  *   Any resending of retried commands, commits or rollbacks should also be ignored by the entities, easily
  *   accomplished with 'become' state changes.
  */
class PersistentSagaActor(persistentEntityRegion: ActorRef, nodeEventTag: EventTag)
  extends Timers with PersistentActor with ActorLogging {

  import PersistentSagaActor._
  import PersistentSagaActorCommands._
  import PersistentSagaActorEvents._
  import SagaStates._
  import TaggedEventSubscription._
  import TransientTaggedEventSubscription._

  implicit def ec: ExecutionContext = context.system.dispatcher
  override def persistenceId: String = EntityPrefix + self.path.name
  private val transactionId = self.path.name
  context.setReceiveTimeout(10.seconds)

  // How long to stick around for reporting purposes after completion.
  private val keepAliveAfterCompletion: FiniteDuration =
    context.system.settings.config.getDuration("akka-saga.bank-account.saga.keep-alive-after-completion").toNanos.nanos

  private var state: SagaState = null

  final override def receiveCommand: Receive = uninitialized.orElse(stateReporting)

  /**
    * In this state we are hobbled until we are sent the start message. Instantiation of this actor has to be in two
    * steps since the edge, in this case the restful route, must assign the transactionId, which automatically
    * becomes the persistentId. Since cluster sharding only allows construction with objects known when the app
    * starts, we have to send the commands as a second step.
    */
  private def uninitialized: Receive = {
    case StartSaga(transactionId, description, commands) =>
      persist(SagaStarted(transactionId, description, commands, nodeEventTag)) { event =>
        applyEvent(event)
        applySideEffectsToPending(commands)
      }
    case ReceiveTimeout =>
      log.error(s"saga for transaction $transactionId never received StartSaga command.")
      context.stop(self)
  }

  /**
    * The pending state. No commit OR rollback will occur until all pending events are in place, as per a Saga.
    * Here we receive event subscription messages applicable to "pending".
    */
  private def pending: Receive = {
    case EventConfirmed(_, transactionId, envelope) if transactionId == self.path.name =>
      envelope match {
        case started: TransactionStarted =>
          envelope.event match {
            case _: TransactionalExceptionEvent =>
              if (!state.exceptions.exists(_.entityId == envelope.entityId)) {
                persist(SagaExceptionConfirmed(transactionId, envelope)) { event =>
                  applyEvent(event)
                  applySideEffectsFromPending(started)
                }
              }
            case _ =>
              if (!state.pendingConfirmed.contains(envelope.entityId)) {
                persist(SagaPendingConfirmed(transactionId, envelope.entityId)) { event =>
                  applyEvent(event)
                  applySideEffectsFromPending(started)
                }
              }
          }
      }
  }

  /**
    * The committing state. When in this state we can only repeatedly attempt to commit. This transaction will remain
    * alive until commits have occurred across the board.
    * Here we receive event subscription messages applicable to "committing".
    */
  private def committing: Receive = {
    case EventConfirmed(_, transactionId, envelope) if transactionId == self.path.name =>
      envelope match {
          case _: TransactionCleared =>
            if (!state.commitConfirmed.contains(envelope.entityId)) {
              persist(SagaCommitConfirmed(transactionId, envelope.entityId)) { event =>
                applyEvent(event)
                applySideEffectsFromCommitting()
              }
            }
        }
  }

  /**
    * The rolling back state. When in this state we can only repeatedly attempt to rollback. This transaction will remain
    * alive until rollbacks have occurred across the board.
    * Here we receive event subscription messages applicable to "rollingBack".
    */
  private def rollingBack: Receive = {
    case EventConfirmed(_, transactionId, envelope) if transactionId == self.path.name =>
      envelope match {
          case _: TransactionReversed =>
            if (!state.rollbackConfirmed.contains(envelope.entityId)) {
              persist(SagaRollbackConfirmed(transactionId, envelope.entityId)) { event =>
                applyEvent(event)
                applySideEffectsFromRollingBack()
              }
            }
        }
  }

  /**
    * Report current state for ease of testing.
    */
  private def stateReporting: Receive = {
    case GetSagaState => sender() ! state
  }

  /**
    * Side effecting transition from Uninitialized to Pending state.
    * --DO NOT call this from recover.
    */
  private def applySideEffectsToPending(commands: Seq[TransactionalCommand]): Unit = {
    log.info(s"starting new saga with transactionId: $transactionId")
    context.system.eventStream.subscribe(self, classOf[EventConfirmed])

    commands.foreach ( cmd =>
      persistentEntityRegion ! StartTransaction(state.transactionId, cmd)
    )
  }

  /**
    * Side effecting transition from pending to the next state.
    * --DO NOT call this from recover.
    */
  private def applySideEffectsFromPending(envelope: TransactionStarted): Unit = {
    envelope.event match {
      case _: TransactionalExceptionEvent =>
        log.error(s"Transaction rolling back when possible due to exception on account ${envelope.entityId}.")
      case _ =>
    }

    if (state.currentState == Committing)
      state.commands.foreach ( cmd =>
        persistentEntityRegion ! CommitTransaction(state.transactionId, cmd.entityId)
      )
    else if (state.currentState == RollingBack) {
      state.pendingConfirmed.foreach(entityId =>
        persistentEntityRegion ! RollbackTransaction(state.transactionId, entityId)
      )

      state.exceptions.foreach(ex =>
        persistentEntityRegion ! CompleteTransaction(state.transactionId, ex.entityId)
      )
    }
    else if (state.currentState == Complete) {
      context.become(stateReporting.orElse {
        case ReceiveTimeout =>
          context.stop(self)
      })
    }
  }

  /**
    * Side effecting transition from committing state.
    * --DO NOT call this from recover.
    */
  private def applySideEffectsFromCommitting(): Unit =
    if (state.currentState == Complete)
      log.info(s"Bank account saga completed successfully for transactionId: ${state.transactionId}")

  /**
    * Side effecting transition from rolling back state.
    * --DO NOT call this from recover.
    */
  private def applySideEffectsFromRollingBack(): Unit =
    if (state.currentState == Complete)
      log.info(s"Bank account saga rolled back successfully for transactionId: ${state.transactionId}")

  /**
    * Apply SagaStarted event.
    */
  private def applyEvent(event: SagaStarted): Unit = {
    state = SagaState(transactionId, event.originalEventTag, event.description, Pending, event.commands)
    context.become(pending.orElse(transientTaggedEventSubscriptionRestart).orElse(stateReporting))
    conditionallySpinUpEventSubscriber(state.originalEventTag)
  }

  /**
    * Checks and conditionally moves to rollback.
    */
  private def checkRollbackCondition(): Unit =
    if (state.exceptions.size == state.commands.size) {
      state = state.copy(currentState = Complete)
      context.become(stateReporting.orElse {
        case ReceiveTimeout =>
          context.stop(self)
      })
    }
    else if ((state.pendingConfirmed.size + state.exceptions.size == state.commands.size) && !state.exceptions.isEmpty) {
      state = state.copy(currentState = RollingBack)
      context.become(rollingBack.orElse(transientTaggedEventSubscriptionRestart).orElse(stateReporting))
    }

  /**
    * Apply SagaPendingConfirmed event.
    */
  private def applyEvent(event: SagaPendingConfirmed): Unit = {
    state = state.copy(pendingConfirmed = state.pendingConfirmed :+ event.entityId)

    if ((state.pendingConfirmed.size + state.exceptions.size == state.commands.size) && state.exceptions.isEmpty) {
      state = state.copy(currentState = Committing)
      context.become(committing.orElse(transientTaggedEventSubscriptionRestart).orElse(stateReporting))
    }
    else
      checkRollbackCondition()
  }

  /**
    * Apply SagaExceptionConfirmed event.
    */
  private def applyEvent(event: SagaExceptionConfirmed): Unit = {
    state = state.copy(exceptions = state.exceptions :+ event.envelope)
    checkRollbackCondition()
  }

  /**
    * Apply SagaCommitConfirmed event.
    */
  private def applyEvent(event: SagaCommitConfirmed): Unit = {
    state = state.copy(commitConfirmed = state.commitConfirmed :+ event.entityId)

    if (state.commitConfirmed.size == state.commands.size) {
      state = state.copy(currentState = Complete)
      context.setReceiveTimeout(keepAliveAfterCompletion)
      context.become(stateReporting.orElse {
        case ReceiveTimeout =>
          context.stop(self)
      })
    }
    else {
      context.become(committing.orElse(stateReporting))
    }
  }

  /**
    * Apply SagaRollbackConfirmed event.
    */
  private def applyEvent(event: SagaRollbackConfirmed): Unit = {
    state = state.copy(rollbackConfirmed = state.rollbackConfirmed :+ event.entityId)

    if (state.rollbackConfirmed.size == state.commands.size - state.exceptions.size) {
      state = state.copy(currentState = Complete)
      context.setReceiveTimeout(keepAliveAfterCompletion)

      context.become(stateReporting.orElse {
        case ReceiveTimeout =>
          context.stop(self)
      })
    }
    else {
      context.become(rollingBack.orElse(stateReporting))
    }
  }

  final override def receiveRecover: Receive = {
    case started: SagaStarted =>
      applyEvent(started)
    case pending: SagaPendingConfirmed =>
      applyEvent(pending)
    case exception: SagaExceptionConfirmed =>
      applyEvent(exception)
    case commit: SagaCommitConfirmed =>
      applyEvent(commit)
    case rollback: SagaRollbackConfirmed =>
      applyEvent(rollback)
    case RecoveryCompleted =>
      if (state != null)
        if (state.currentState != Complete && state.currentState !=Uninitialized)
          context.system.eventStream.subscribe(self, classOf[EventConfirmed])
  }

  /**
    * Mix this into receives awaiting transaction completions to ensure a transient subscription is
    * always up across timeouts.
    */
  private def transientTaggedEventSubscriptionRestart(): Receive = {
    case TransientTaggedEventSubscriptionTimedOut(transientEventTag)
      if transientEventTag == state.originalEventTag => conditionallySpinUpEventSubscriber(state.originalEventTag)
  }

  /**
    * In the case that this saga has restarted on or been moved to another node, will ensure that there is an event
    * subscriber for the original eventTag.
    */
  private def conditionallySpinUpEventSubscriber(originalEventTag: EventTag): Unit = {
    if (originalEventTag != nodeEventTag) {
      // Spin up my own event subscriber, unless one already exists.
      val duration: FiniteDuration = context.system.settings.config
        .getDuration("akka-saga.saga.event-subscription-lookup-timeout").toNanos.nanos
      implicit val timeout = Timeout(duration)

      (context.actorSelection(Constants.taggedEventSubscriptionActorPrefix + s"/$originalEventTag")
        .resolveOne()).recover {
        case ActorNotFound(_) => context.system.actorOf(TransientTaggedEventSubscription.props(nodeEventTag),
          Constants.taggedEventSubscriptionActorPrefix + s"/$nodeEventTag")
      }
    }
  }
}
