package com.akkatransactional.typed

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.Effect
import com.akkatransactional.typed.TransactionalEntityProtocol.TransactionalCommandEnvelope
import org.slf4j.Logger

/**
  * Protocol for use in entities taking part in a transaction.
  */
object TransactionalEntityProtocol {
  // Trait for any entity commands participating in a transaction. All participating domain commands must extend this.
  trait EntityTransactionalCommand {
    def entityId: String
  }

  // The contract from this transactional actor to the entities.
  sealed trait TransactionalCommandEnvelope {
    def transactionId: String
    def entityType: String
    def entityId: String
  }

  // Command impls from the transactional actor to the entities.
  final case class StartEntityTransaction(transactionId: String, entityType: String, entityId: String,
                                          command: EntityTransactionalCommand) extends TransactionalCommandEnvelope
  final case class RollbackEntityTransaction(transactionId: String, entityType: String, entityId: String)
                                      extends TransactionalCommandEnvelope
  final case class CommitEntityTransaction(transactionId: String, entityType: String, entityId: String)
                                           extends TransactionalCommandEnvelope

  // Trait for any entity events participating in a transaction.
  trait EntityEvent {
    def transactionId: String
    def entityType: String
    def entityId: String
  }

  // Marker traits in order to classify the events and route accordingly.
  trait EntityPendingEvent extends EntityEvent
  trait EntityErrorEvent extends EntityEvent
  trait EntityClearingEvent extends EntityEvent
  trait EntityReversalEvent extends EntityEvent
}

/**
  * Abstraction around shards, etc.
  */
trait EntityLocator {
  def sendCommand(command: TransactionalCommandEnvelope): Unit
}

/**
  * The transactional actor that lives through the entire process until completion.
  * Note: ignoring retry for now, it's very expensive. I'm thinking instead of classic retry that we just send a
  * rollback and have it ignored by entities that did not receive the original start of transaction.
  */
object TransactionalActor {

  import TransactionalEntityProtocol._

  // Command protocol for this transactional actor.
  sealed trait TransactionCommand
  final case class AddCommand(transactionId: String, entityType: String, entityId: String,
                              command: EntityTransactionalCommand, sequence: Int) extends TransactionCommand
  final case class StartTransaction(transactionId: String, description: String) extends TransactionCommand
  final case class EndCommands(transactionId: String, sequence: Int) extends TransactionCommand
  final case class ConfirmEntityEvent(transactionId: String, event: EntityPendingEvent) extends TransactionCommand
  final case class ReportEntityError(transactionId: String, event: EntityErrorEvent) extends TransactionCommand
  final case class ConfirmEntityClearing(transactionId: String, event: EntityClearingEvent) extends TransactionCommand
  final case class ConfirmEntityReversal(transactionId: String, event: EntityReversalEvent) extends TransactionCommand

  trait TransactionEvent {
    def transactionId: String
  }

  // Event protocol for this transactional actor.
  final case class TransactionStarted(transactionId: String, description: String) extends TransactionEvent
  final case class CommandAdded(transactionId: String, entityId: String, entityType: String,
                          command: EntityTransactionalCommand, sequence: Int) extends TransactionEvent
  final case class CommandsEnded(transactionId: String, sequence: Long) extends TransactionEvent
  final case class EntityEventConfirmed(transactionId: String, entityEvent: EntityEvent) extends TransactionEvent
  final case class EntityErrorReported(transactionId: String, entityEvent: EntityEvent) extends TransactionEvent
  final case class EntityClearingConfirmed(transactionId: String, entityEvent: EntityEvent) extends TransactionEvent
  final case class EntityReversalConfirmed(transactionId: String, entityEvent: EntityEvent) extends TransactionEvent
  final case class TransactionError(transactionId: String) extends TransactionEvent
  final case class TransactionComplete(transactionId: String) extends TransactionEvent

  sealed trait TransactionState {
    def transactionId: String
  }

  final case class EmptyState(transactionId: String) extends TransactionState

  private final case class EntityTypeAndId(entityType: String, entityId: String)

  /**
    * In this state, the entity commands are streamed in and sent to the entities until the end of commands message.
    */
  final case class PendingState(
    transactionId: String,
    sequence: Int = 0,
    outOfSequenceError: Boolean = false,
    entities: Seq[EntityTypeAndId] = Nil,
    confirmed: Seq[EntityTypeAndId] = Nil,
    exceptions: Seq[EntityTypeAndId] = Nil
  ) extends TransactionState {

    def withCommandAdded(event: CommandAdded): PendingState =
      if (validSequence(event.sequence))
        copy(entities = entities :+ EntityTypeAndId(event.entityType, event.entityId),
          sequence = event.sequence)
      else
        copy(entities = entities :+ EntityTypeAndId(event.entityType, event.entityId),
          outOfSequenceError = true)

    def withEntityEventConfirmed(event: EntityEventConfirmed): PendingState =
      confirmed.find(p => p.entityType == event.entityEvent.entityType
                    && p.entityId == event.entityEvent.entityId) match {
        case Some(_) => this
        case None => copy(confirmed = confirmed :+ EntityTypeAndId("", ""))
      }

    def validSequence(sequence: Long): Boolean =
      if (this.sequence == 0 && sequence == 1 || (this.sequence - sequence == 1))
        true
      else false

    def isCommitCondition(): Boolean =
      if (entities.size == confirmed.size && !outOfSequenceError)
        true
      else false

    def isRollbackCondition(): Boolean =
      if ((entities.size == (confirmed.size - exceptions.size)))
        true
      else false

    def sideEffect(entityLocator: EntityLocator, entityType: String, entityId: String,
                   command: EntityTransactionalCommand): Unit =
      entityLocator.sendCommand(StartEntityTransaction(transactionId, entityType, entityId, command))

    def sideEffects(entityLocator: EntityLocator): Unit =
      if (isCommitCondition())
        entities.foreach( e =>
          entityLocator.sendCommand(CommitEntityTransaction(transactionId, e.entityType, e.entityId))
        )
      else if (isRollbackCondition()) {
        entities.foreach( e =>
          // It's ok to send to entities that previously emitted an error, the rollback will be ignored.
          entityLocator.sendCommand(RollbackEntityTransaction(transactionId, e.entityType, e.entityId))
        )
      }
  }

  /**
    * In the commit state, the commit commands have all been sent to the entities and we await confirmation.
    */
  final case class CommitState(
    transactionId: String,
    sent: Seq[EntityTypeAndId] = Nil,
    confirmed: Seq[EntityTypeAndId] = Nil) extends TransactionState {

    def canComplete(): Boolean =
      if (sent.size == confirmed.size)
        true
      else false
  }

  /**
    * In the rollback state, the rollback commands have all been sent to the entities and we await confirmation.
    */
  final case class RollbackState(
    transactionId: String,
    sent: Seq[EntityTypeAndId] = Nil,
    confirmed: Seq[EntityTypeAndId] = Nil) extends TransactionState {

    def canComplete: Boolean =
      if (sent.size == confirmed.size)
        true
      else false
  }

  def apply(transactionId: String, entityLocator: EntityLocator): Behavior[TransactionCommand] =
    Behaviors.setup { context =>
      EventSourcedBehavior[TransactionCommand, TransactionEvent, TransactionState](
        persistenceId = PersistenceId.ofUniqueId(transactionId),
        emptyState = EmptyState(transactionId),
        commandHandler = commandHandler(transactionId, entityLocator, context.log),
        eventHandler = eventHandler(context.log))
    }

    private def commandHandler(transactionId: String, entityLocator: EntityLocator, log: Logger):
                              (TransactionState, TransactionCommand) => Effect[TransactionEvent, TransactionState] =
                              { (state, command) =>
      state match {
        case EmptyState(_) =>
          command match {
            case StartTransaction(_, description) =>
              log.debug(s"Starting transaction $transactionId")
              Effect.persist(TransactionStarted(transactionId, description))
          }
        case pending: PendingState =>
          command match {
            case AddCommand(transactionId, entityType, entityId, command, sequence) =>
              log.debug(s"Adding command to $entityType::$entityId to transaction $transactionId")
              Effect.persist(CommandAdded(transactionId, entityType, entityId, command, sequence))
                .thenRun( _ =>
                  if (pending.validSequence(sequence))
                    pending.sideEffect(entityLocator, entityType, entityId, command)
                  else {
                    log.error(s"Out of sequence error for transaction $transactionId, sequence numbers must start with 1 and be contiguous")
                    Effect.persist(TransactionError(transactionId))
                  }
                )
            case EndCommands(_, sequence) =>
              log.debug(s"Ending transaction $transactionId")
              Effect.persist(CommandsEnded(transactionId, sequence)).thenRun( _ =>
                pending.sideEffects(entityLocator)
              )
            case ConfirmEntityEvent(_, event) =>
              log.debug(s"Received $event for transaction $transactionId")
              Effect.persist(EntityEventConfirmed(transactionId, event)).thenRun( _ =>
                pending.sideEffects(entityLocator)
              )
            case ReportEntityError(_, event) =>
              log.error(s"Received error $event for transaction $transactionId")
              Effect.persist(EntityErrorReported(transactionId, event)).thenRun( _ =>
                pending.sideEffects(entityLocator)
              )
          }
        case CommitState(transactionId, _, _) =>
          command match {
            case ConfirmEntityClearing(_, event) =>
              Effect.persist(EntityClearingConfirmed(transactionId, event))
              // todo: side effects
            case ReportEntityError(_, event) =>
              log.error(s"Received error $event for transaction $transactionId")
              Effect.persist(EntityErrorReported(transactionId, event))
              // todo: side effects
          }
        case RollbackState(transactionId, _, _) =>
          command match {
            case ConfirmEntityReversal(_, event) =>
              Effect.persist(EntityReversalConfirmed(transactionId, event))
              // todo: side effects
            case ReportEntityError(_, event) =>
              log.error(s"Received error $event for transaction $transactionId")
              Effect.persist(EntityErrorReported(transactionId, event))
              // todo: side effects
          }
      }
    }

  private def eventHandler(log: Logger): (TransactionState, TransactionEvent) => TransactionState = { (state, event) =>
    state match {
      case EmptyState(_) =>
        event match {
          case TransactionStarted(transactionId, _) =>
            log.info(s"Started transaction $transactionId")
            PendingState(transactionId)
          case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
        }
      case pending @ PendingState(transactionId, _, _, _, _, _) =>
        event match {
          case added @ CommandAdded(_, entityType, entityId, command, _) =>
            log.info(s"Added command to $entityType::$entityId to transaction $transactionId")
            pending.withCommandAdded(added)
          case confirmed @ EntityEventConfirmed(transactionId, entityEvent) =>
            log.info(s"Command confirmed to ${confirmed.entityEvent.entityType}::${confirmed.entityEvent.entityId} to transaction $transactionId")
            val withConfirmed = pending.withEntityEventConfirmed(confirmed)

            if (withConfirmed.isCommitCondition())
                CommitState(transactionId, pending.entities)
            else if (withConfirmed.isRollbackCondition())
                RollbackState(transactionId, pending.entities)
            else
                withConfirmed

//          case _: EntityErrorReported =>
//
//            RollbackState(transactionId, pending.sequence)
          case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
        }
      case commit @ CommitState(transactionId, _, _) =>
        event match {
          case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
        }
      case rollback @ RollbackState(transactionId, _, _) =>
        event match {
          case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
        }
    }
  }
}
