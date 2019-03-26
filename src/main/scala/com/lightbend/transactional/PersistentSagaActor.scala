package com.lightbend.transactional

import akka.actor.{ActorLogging, ActorRef, Props, ReceiveTimeout, Timers}
import akka.pattern.ask
import akka.persistence.{PersistentActor, RecoveryCompleted}
import akka.util.Timeout
import com.lightbend.transactional.lightbend.PersistenceId

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * Companion object.
  */
object PersistentSagaActor {

  val EntityPrefix = "persistent-saga-actor-"

  /**
    * Use this for asks between saga and entities.
    */
  case class Ack()

  /**
    * Props factory method.
    */
  def props(persistentEntityRegion: ActorRef): Props =
    Props(new PersistentSagaActor(persistentEntityRegion))
}

/**
  * This is effectively a long lived transaction that operates within an Akka cluster. Classic saga patterns
  * will be followed, such as retrying rollback over and over as well as retry of transactions over and over if
  * necessary, before rollback.
  * --Retry is exhaustive in that all events will be resubscribed and ignored if already processed.
  *   Any resending of retried commands, commits or rollbacks should also be ignored by the entities, easily
  *   accomplished with 'become' state changes.
  */
class PersistentSagaActor(persistentEntityRegion: ActorRef)
  extends Timers with PersistentActor with ActorLogging {

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
    commands: Seq[TransactionalCommand] = Seq.empty,
    pendingConfirmed: Seq[PersistenceId] = Seq.empty,
    commitConfirmed: Seq[PersistenceId] = Seq.empty,
    rollbackConfirmed: Seq[PersistenceId] = Seq.empty,
    exceptions: Seq[TransactionalEventEnvelope] = Seq.empty,
    currentStateAcks: Seq[PersistenceId] = Seq.empty)

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
      persist(SagaStarted(transactionId, description, commands)) { event =>
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
    case started @ TransactionStarted(_, entityId, _) =>
      started.event match {
        case _: TransactionalExceptionEvent =>
          if (!state.exceptions.exists(_.entityId == entityId)) {
            persist(started) { event =>
              applyTransactionStartedException(event)
              persistentEntityRegion ! SagaDeliveryReceipt(entityId)
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
    case cleared @ TransactionCleared(_, entityId) =>
      if (!state.commitConfirmed.contains(entityId)) {
        persist(cleared) { event =>
          persistentEntityRegion ! SagaDeliveryReceipt(entityId)
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
    case reversed @ TransactionReversed(_, entityId) =>
      if (!state.rollbackConfirmed.contains(entityId)) {
        persist(reversed) { event =>
          applyTransactionReversed(event)
          persistentEntityRegion ! SagaDeliveryReceipt(entityId)
        }
      }
  }

  /**
    * Apply SagaStarted event.
    */
  private def applySagaStarted(event: SagaStarted): Unit = {
    state = SagaState(event.transactionId, event.description, "pending", event.commands)
    context.become(pending)
  }

  /**
    * Side effecting transition from Uninitialized to Pending state.
    */
  private def applySagaStartedSideEffects(transactionId: String, commands: Seq[TransactionalCommand]): Unit = {
    log.info(s"starting new saga with transactionId: $transactionId")

    commands.foreach ( cmd =>
      persistentEntityRegion ! StartTransaction(state.transactionId, cmd.entityId, cmd)
    )
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
      state = state.copy(currentState = "committing", currentStateAcks = Nil)
      context.become(committing)
    }
    else if (rollbackCondition()) {
      state = state.copy(currentState = "rollingBack", currentStateAcks = Nil)
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
        implicit val timeout: Timeout = Timeout(10.seconds)
        (persistentEntityRegion ? SagaDeliveryReceipt(started.entityId)).mapTo[Ack].foreach( _ =>
          state = state.copy(currentStateAcks = state.currentStateAcks :+ started.entityId) // TODO: for use in retry later
        )
    }

    if (commitCondition()) {
      state = state.copy(currentState = "committing", currentStateAcks = Nil)
      context.become(committing)
    }
    else if (rollbackCondition()) {
      state = state.copy(currentState = "rollingBack", currentStateAcks = Nil)
      context.become(rollingBack)
    }

    if (state.currentState == "committing")
      state.commands.foreach(cmd =>
        persistentEntityRegion ! CommitTransaction(state.transactionId, cmd.entityId)
      )
    else if (state.currentState == "rollingBack") {
      state.pendingConfirmed.foreach(entityId =>
        persistentEntityRegion ! RollbackTransaction(state.transactionId, entityId)
      )
    }
  }

  /**
    * Apply TransactionStarted exception event.
    */
  private def applyTransactionStartedException(event: TransactionStarted): Unit = {
    state = state.copy(exceptions = state.exceptions :+ event)
    if (rollbackCondition()) {
      state = state.copy(currentState = "rollingBack", currentStateAcks = Nil)
      context.become(rollingBack)
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
      state = state.copy(currentState = "committing", currentStateAcks = Nil)
      context.become(committing)
    }
  }

  /**
    * Apply TransactionReversed event.
    */
  private def applyTransactionReversed(event: TransactionReversed): Unit = {
    state = state.copy(rollbackConfirmed = state.rollbackConfirmed :+ event.entityId)

    if (completionCondition())
      context.stop(self)
  }

  final override def receiveRecover: Receive = {
    case started: SagaStarted =>
      applySagaStarted(started)
    case exception: TransactionStarted if exception.getClass.isAssignableFrom(classOf[TransactionalExceptionEvent]) =>
      applyTransactionStartedException(exception)
    case started: TransactionStarted =>
      applyTransactionStarted(started)
    case cleared: TransactionCleared =>
      applyTransactionCleared(cleared)
    case reversed: TransactionReversed =>
      applyTransactionReversed(reversed)
    case RecoveryCompleted =>
      // Handle here for retry??
  }

  /**
    * Checks and conditionally moves to rollback.
    */
  private def commitCondition(): Boolean =
    if (state.currentStateAcks.size == state.commands.size + state.exceptions.size)
      true
    else
      false

  /**
    * Checks and conditionally moves to rollback.
    */
  private def rollbackCondition(): Boolean =
    if (state.currentStateAcks.size == state.commands.size + state.exceptions.size)
      true
    else
      false

  /**
    * Checks for completion.
    */
  private def completionCondition(): Boolean =
    if (state.currentStateAcks.size == state.commands.size + state.exceptions.size)
      true
    else
      false
}
