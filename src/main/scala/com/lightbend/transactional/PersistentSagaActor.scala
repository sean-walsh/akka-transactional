package com.lightbend.transactional

import akka.actor.{ActorLogging, ActorNotFound, ActorRef, Props, ReceiveTimeout}
import akka.persistence.PersistentActor
import akka.util.Timeout
import com.lightbend.transactional.lightbend.PersistenceId

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * Companion object.
  */
object PersistentSagaActor {

  final val EntityPrefix = "persistent-saga-"

  final val RegionName = "persistent-saga"

  /**
    * Use this for asks between saga and entities.
    */
  case class Ack()

  /**
    * Props factory method.
    */
  def props(persistentEntityRegion: ActorRef, nodeEventTag: String): Props =
    Props(new PersistentSagaActor(persistentEntityRegion, nodeEventTag))
}

/**
  * This is effectively a long lived transaction that operates within an Akka cluster. Classic saga patterns
  * will be followed, such as retrying rollback over and over as well as retry of transactions over and over if
  * necessary, before rollback.
  */
class PersistentSagaActor(persistentEntityRegion: ActorRef, nodeEventTag: String)
  extends PersistentActor with ActorLogging {

  import PersistentSagaActor._
  import PersistentSagaActorCommands._
  import PersistentSagaActorEvents._

  implicit def ec: ExecutionContext = context.system.dispatcher
  override def persistenceId: String = self.path.name

  context.setReceiveTimeout(10.seconds)

  private case class SagaState(
    transactionId: String,
    description: String,
    currentState: String,
    originalEventTag: String,
    commands: Seq[TransactionalCommand] = Seq.empty,
    pendingConfirmed: Seq[PersistenceId] = Seq.empty,
    commitConfirmed: Seq[PersistenceId] = Seq.empty,
    rollbackConfirmed: Seq[PersistenceId] = Seq.empty,
    exceptions: Seq[TransactionalEventEnvelope] = Seq.empty)

  private var state: SagaState = null

  override def receiveCommand: Receive = uninitialized

  /**
    * In this state we are hobbled until we are sent the start message. Instantiation of this actor has to be in two
    * steps since the edge, in this case the restful route, must assign the transactionId, which automatically
    * becomes the persistentId. Since cluster sharding only allows construction with objects known when the app
    * starts, we have to send the commands as a second step.
    */
  private def uninitialized: Receive = {
    case StartSaga(transactionId, description, commands) =>
      persist(SagaStarted(transactionId, description, nodeEventTag, commands)) { event =>
        applySagaStarted(event)
        applySagaStartedSideEffects(transactionId, commands)
        sender() ! Ack
      }
    case ReceiveTimeout =>
      log.error(s"saga for saga ${self.path.name} never received StartSaga command.")
      context.stop(self)
  }

  /**
    * The pending state. No commit OR rollback will occur until all pending events are in place, as per a Saga.
    * Here we receive event subscription messages applicable to "pending".
    */
  private def pending: Receive = {
    case started @ TransactionStarted(_, entityId, _, _) =>
      started.event match {
        case _: TransactionalExceptionEvent =>
          if (!state.exceptions.exists(_.entityId == entityId)) {
            persist(started) { event =>
              applyTransactionStarted(event)
              applyTransactionStartedEventSideEffects(started)
            }
          }
        case _ =>
          if (!state.pendingConfirmed.contains(entityId)) {
            persist(started) { event =>
              applyTransactionStarted(event)
              applyTransactionStartedEventSideEffects(started)
            }
          }
      }
  }

  /**
    * The committing state. When in this state we can only repeatedly attempt to commit. This transaction will remain
    * alive until commits have occurred across the board.
    * Here we receive messages from the entities applicable to "committing".
    */
  private def committing: Receive = {
    case cleared @ TransactionCleared(_, entityId, _) =>
      if (!state.commitConfirmed.contains(entityId)) {
        persist(cleared) { event =>
          applyTransactionCleared(event)
        }
      }
  }

  /**
    * The rolling back state. When in this state we can only repeatedly attempt to rollback. This transaction will remain
    * alive until rollbacks have occurred across the board.
    * Here we receive event subscription messages applicable to "rollingBack".
    */
  private def rollingBack: Receive = {
    case reversed @ TransactionReversed(_, entityId, _) =>
      if (!state.rollbackConfirmed.contains(entityId)) {
        persist(reversed) { event =>
          applyTransactionReversed(event)
        }
      }
  }

  /**
    * Apply SagaStarted event.
    */
  private def applySagaStarted(started: SagaStarted): Unit = {
    state = SagaState(started.transactionId, started.description, "pending", started.nodeEventTag, started.commands)
    context.become(pending)
  }

  /**
    * Side effecting transition from Uninitialized to Pending state.
    */
  private def applySagaStartedSideEffects(transactionId: String, commands: Seq[TransactionalCommand]): Unit = {
    log.info(s"starting new saga with transactionId: $transactionId")

    commands.foreach ( cmd =>
      persistentEntityRegion ! StartTransaction(state.transactionId, cmd.entityId, state.originalEventTag, cmd)
    )

    conditionallySpinUpEventSubscriber(state.originalEventTag)
  }

  /**
    * Apply TransactionStarted event.
    */
  private def applyTransactionStarted(started: TransactionStarted): Unit = {
    started.event match {
      case _: TransactionalExceptionEvent =>
        state = state.copy(exceptions = state.exceptions :+ started)
      case _ =>
        state = state.copy(pendingConfirmed = state.pendingConfirmed :+ started.entityId)
    }

    if (commitCondition()) {
      state = state.copy(currentState = "committing")
      context.become(committing)
    }
    else if (rollbackCondition()) {
      state = state.copy(currentState = "rollingBack")
      context.become(rollingBack)
    }
  }

  /**
    * Side effecting transition due to TransactionStarted event for each entity.
    */
  private def applyTransactionStartedEventSideEffects(started: TransactionStarted): Unit = {
    started.event match {
      case _: TransactionalExceptionEvent =>
        log.info(s"Transaction rolling back when possible due to exception on account ${started.entityId}.")
      case _ =>
    }

    if (commitCondition()) {
      state = state.copy(currentState = "committing")
      context.become(committing)

      state.commands.foreach(cmd =>
        persistentEntityRegion ! CommitTransaction(state.transactionId, cmd.entityId, state.originalEventTag)
      )
    }
    else if (rollbackCondition()) {
      state = state.copy(currentState = "rollingBack")
      context.become(rollingBack)

      state.pendingConfirmed.foreach(entityId =>
        persistentEntityRegion ! RollbackTransaction(state.transactionId, entityId, state.originalEventTag)
      )
    }
  }

  /**
    * Apply TransactionCleared event.
    */
  private def applyTransactionCleared(event: TransactionCleared): Unit = {
    state = state.copy(commitConfirmed = state.commitConfirmed :+ event.entityId)

    if (completionCondition()) {
      log.info(s"Saga completed successfully for transactionId: ${state.transactionId}")
      context.stop(self)
    }
    else {
      state = state.copy(currentState = "committing")
      context.become(committing)
    }
  }

  /**
    * Apply TransactionReversed event.
    */
  private def applyTransactionReversed(event: TransactionReversed): Unit = {
    state = state.copy(rollbackConfirmed = state.rollbackConfirmed :+ event.entityId)

    if (completionCondition()) {
      log.info(s"Saga completed with rollback for transactionId: ${state.transactionId}")
      context.stop(self)
    }
  }

  final override def receiveRecover: Receive = {
    case started: SagaStarted =>
      applySagaStarted(started)
    case started: TransactionStarted =>
      applyTransactionStarted(started)
    case cleared: TransactionCleared =>
      applyTransactionCleared(cleared)
    case reversed: TransactionReversed =>
      applyTransactionReversed(reversed)
  }

  /**
    * Checks and conditionally moves to rollback.
    */
  private def commitCondition(): Boolean =
    if (state.pendingConfirmed.size == state.commands.size && state.exceptions.isEmpty)
      true
    else
      false

  /**
    * Checks and conditionally moves to rollback.
    */
  private def rollbackCondition(): Boolean =
    if (state.commands.size == state.pendingConfirmed.size + state.exceptions.size && !state.exceptions.isEmpty)
      true
    else
      false

  /**
    * Checks for completion.
    */
  private def completionCondition(): Boolean =
    if (state.currentState == "committing" && state.commitConfirmed.size == state.commands.size)
      true
    else if (state.currentState == "rollingBack" && state.rollbackConfirmed.size == state.commands.size + state.exceptions.size)
      true
    else
      false

  /**
    * In the case that this saga has restarted on or been moved to another node, will ensure that there is an event
    * subscriber for the original eventTag.
    */
  private def conditionallySpinUpEventSubscriber(originalEventTag: String): Unit = {
    if (originalEventTag != nodeEventTag) {
      // Spin up my own event subscriber, unless one already exists.
      val duration: FiniteDuration = context.system.settings.config
        .getDuration("akka-saga.saga.event-subscription-lookup-timeout").toNanos.nanos
      implicit val timeout = Timeout(duration)

      (context.actorSelection(s"${TaggedEventSubscription.ActorNamePrefix}/$originalEventTag")
        .resolveOne()).recover {
        case ActorNotFound(_) => context.system.actorOf(TransientTaggedEventSubscription.props(nodeEventTag),
          s"${TaggedEventSubscription.ActorNamePrefix}/$nodeEventTag")
      }
    }
  }
}
