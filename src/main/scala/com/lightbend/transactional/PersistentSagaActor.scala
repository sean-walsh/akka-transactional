package com.lightbend.transactional

import akka.actor.{ActorLogging, ActorRef, Props, ReceiveTimeout, Timers}
import akka.persistence.{PersistentActor, RecoveryCompleted}
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
  override def persistenceId: String = EntityPrefix + self.path.name
  private val transactionId = self.path.name
  context.setReceiveTimeout(10.seconds)

  private case class SagaState(
    transactionId: String,
    description: String,
    commands: Seq[TransactionalCommand] = Seq.empty,
    pendingConfirmed: Seq[PersistenceId] = Seq.empty,
    commitConfirmed: Seq[PersistenceId] = Seq.empty,
    rollbackConfirmed: Seq[PersistenceId] = Seq.empty,
    exceptions: Seq[TransactionalEventEnvelope] = Seq.empty,
    acks: Seq[PersistenceId] = Seq.empty)

  private var state: SagaState = null

  final override def receiveCommand: Receive = uninitialized

  /**
    * In this state we are hobbled until we are sent the start message. Instantiation of this actor has to be in two
    * steps since the edge, in this case the restful route, must assign the transactionId, which automatically
    * becomes the persistentId. Since cluster sharding only allows construction with objects known when the app
    * starts, we have to send the commands as a second step.
    */
  private def uninitialized: Receive = {
    case StartSaga(transactionId, description, commands) =>
      persist(SagaStarted(transactionId, description, commands)) { event =>
        applySagaStartedEvent(event)
        applySideEffectsToPending(commands)
        sender() ! Ack
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
    case _ @ EventConfirmationSentToSaga(deliveryId, transactionId, envelope) =>
      envelope match {
        case started: TransactionStarted =>
          started.event match {
            case _: TransactionalExceptionEvent =>
              if (!state.exceptions.exists(_.entityId == envelope.entityId)) {
                persist(SagaExceptionConfirmed(transactionId, envelope)) { event =>
                  applySagaExceptionConfirmedEvent(event)
                  sender() ! SagaDeliveryReceipt(deliveryId)
                  applyTransactionStartedEventSideEffects(started)
                }
              }
            case _ =>
              if (!state.pendingConfirmed.contains(envelope.entityId)) {
                persist(SagaPendingConfirmed(transactionId, envelope.entityId)) { event =>
                  applySagaPendingConfirmedEvent(event)
                  sender() ! SagaDeliveryReceipt(deliveryId)
                  applyTransactionStartedEventSideEffects(started)
                }
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
    case EventConfirmationSentToSaga(deliveryId, transactionId, envelope) =>
      envelope match {
          case _: TransactionCleared =>
            if (!state.commitConfirmed.contains(envelope.entityId)) {
              persist(SagaCommitConfirmed(transactionId, envelope.entityId)) { event =>
                applySagaCommitConfirmedEvent(event)
                sender() ! SagaDeliveryReceipt(deliveryId)
                //applyTransactionClearedSideEffects()
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
    case EventConfirmationSentToSaga(deliveryId, transactionId, envelope) =>
      envelope match {
          case _: TransactionReversed =>
            if (!state.rollbackConfirmed.contains(envelope.entityId)) {
              persist(SagaRollbackConfirmed(transactionId, envelope.entityId)) { event =>
                sender() ! SagaDeliveryReceipt(deliveryId)
                applySagaRollbackConfirmedEvent(event)
              }
            }
        }
  }

  /**
    * Side effecting transition from Uninitialized to Pending state.
    * --DO NOT call this from recover.
    */
  private def applySideEffectsToPending(commands: Seq[TransactionalCommand]): Unit = {
    log.info(s"starting new saga with transactionId: $transactionId")
    context.system.eventStream.subscribe(self, classOf[EventConfirmationSentToSaga])

    commands.foreach ( cmd =>
      persistentEntityRegion ! StartTransaction(state.transactionId, cmd)
    )
  }

  /**
    * Side effecting transition due to TransactionStarted event for each entity.
    */
  private def applyTransactionStartedEventSideEffects(started: TransactionStarted): Unit = {
    started.event match {
      case _: TransactionalExceptionEvent =>
        log.error(s"Transaction rolling back when possible due to exception on account ${started.entityId}.")
      case _ =>
    }

    if (this.receive == committing)
      state.commands.foreach ( cmd =>
        persistentEntityRegion ! CommitTransaction(state.transactionId, cmd.entityId)
      )
    else if (this.receive == rollingBack) {
      state.pendingConfirmed.foreach(entityId =>
        persistentEntityRegion ! RollbackTransaction(state.transactionId, entityId)
      )
    }
    else {
      log.info(s"Bank account saga completed successfully for transactionId: ${state.transactionId}")
      context.stop(self)
    }
  }

  /**
    * Apply SagaStarted event.
    */
  private def applySagaStartedEvent(event: SagaStarted): Unit = {
    state = SagaState(transactionId, event.description, event.commands)
    context.become(pending)
  }

  /**
    * Checks and conditionally moves to rollback.
    */
  private def checkRollbackCondition(): Unit =
    if (state.exceptions.size == state.commands.size)
      context.stop(self)
    else if ((state.pendingConfirmed.size + state.exceptions.size == state.commands.size) && !state.exceptions.isEmpty)
      context.become(rollingBack)

  /**
    * Apply SagaPendingConfirmed event.
    */
  private def applySagaPendingConfirmedEvent(event: SagaPendingConfirmed): Unit = {
    state = state.copy(pendingConfirmed = state.pendingConfirmed :+ event.entityId)

    if ((state.pendingConfirmed.size + state.exceptions.size == state.commands.size) && state.exceptions.isEmpty)
      context.become(committing)
    else
      checkRollbackCondition()
  }

  /**
    * Apply SagaExceptionConfirmed event.
    */
  private def applySagaExceptionConfirmedEvent(event: SagaExceptionConfirmed): Unit = {
    state = state.copy(exceptions = state.exceptions :+ event.envelope)
    checkRollbackCondition()
  }

  /**
    * Apply SagaCommitConfirmed event.
    */
  private def applySagaCommitConfirmedEvent(event: SagaCommitConfirmed): Unit = {
    state = state.copy(commitConfirmed = state.commitConfirmed :+ event.entityId)

    if (state.commitConfirmed.size == state.commands.size)
      context.stop(self)
    else
      context.become(committing)
  }

  /**
    * Apply SagaRollbackConfirmed event.
    */
  private def applySagaRollbackConfirmedEvent(event: SagaRollbackConfirmed): Unit = {
    state = state.copy(rollbackConfirmed = state.rollbackConfirmed :+ event.entityId)

    if (state.rollbackConfirmed.size == state.commands.size - state.exceptions.size)
      context.stop(self)
    else
      context.become(rollingBack)
  }

  final override def receiveRecover: Receive = {
    case started: SagaStarted =>
      applySagaStartedEvent(started)
    case pending: SagaPendingConfirmed =>
      applySagaPendingConfirmedEvent(pending)
    case exception: SagaExceptionConfirmed =>
      applySagaExceptionConfirmedEvent(exception)
    case commit: SagaCommitConfirmed =>
      applySagaCommitConfirmedEvent(commit)
    case rollback: SagaRollbackConfirmed =>
      applySagaRollbackConfirmedEvent(rollback)
    case RecoveryCompleted =>
      if (state != null)
        if (this.receive != uninitialized)
          context.system.eventStream.subscribe(self, classOf[EventConfirmationSentToSaga])
  }
}
