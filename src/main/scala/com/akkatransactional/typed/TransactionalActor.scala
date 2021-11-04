package com.akkatransactional.typed

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.Effect
import com.akkatransactional.typed.TransactionalEntityProtocol.EntityCommand
import org.slf4j.Logger

/**
  * Protocol for use in entities taking part in a transaction.
  */
object TransactionalEntityProtocol {
  // Trait for any entity commands participating in a transaction.
  trait EntityCommand {
    def entityId: String
    def entityType: String // Maps to a shard region.
  }

  // Trait for any entity events participating in a transaction.
  trait EntityTransactionEvent

  final case class EntityTransactionStarted(transactionId: String, entityId: String, event: EntityTransactionEvent)
    extends EntityTransactionEvent

  final case class EntityTransactionReversed(transactionId: String, entityType: String, entityId: String)
    extends EntityTransactionEvent

  final case class EntityTransactionCleared(transactionId: String, entityType: String, entityId: String)
    extends EntityTransactionEvent

  final case class EntityTransactionError(transactionId: String, entityType: String, entityId: String, error: String)
    extends EntityTransactionEvent
}

/**
  * Abstraction around shards, etc.
  */
trait EntityLocator {
  def sendCommand(command: EntityCommand): Unit
}

/**
  * The transactional actor that lives through the entire process until completion.
  * Note: ignoring retry for now, it's very expensive. I'm thinking instead of classic retry that we just send a
  * rollback and have it ignored by entities that did not receive the original start of transaction.
  */
object TransactionalActor {

  import TransactionalEntityProtocol._

  sealed trait TransactionCommand
  final case class AddCommand(transactionId: String, command: EntityCommand, sequence: Int) extends TransactionCommand
  final case class StartTransaction(transactionId: String, description: String) extends TransactionCommand
  final case class EndCommands(transactionId: String, sequence: Int) extends TransactionCommand
  final case class ReportError(transactionId: String, event: EntityTransactionError) extends TransactionCommand
  final case class RecordClearing(transactionId: String, event: EntityTransactionCleared) extends TransactionCommand
  final case class RecordReversal(transactionId: String, event: EntityTransactionReversed) extends TransactionCommand

  trait TransactionEvent {
    def transactionId: String
  }

  case class TransactionStarted(transactionId: String, description: String) extends TransactionEvent
  case class CommandAdded(transactionId: String, command: EntityCommand, sequence: Int) extends TransactionEvent
  case class CommandsEnded(transactionId: String, sequence: Long) extends TransactionEvent
  case class TransactionComplete(transactionId: String) extends TransactionEvent
  case class TransactionErrorCondition(transactionId: String) extends TransactionEvent
  case class TransactionCleared(transactionId: String, entityType: String, entityId: String) extends TransactionEvent
  case class TransactionReversed(transactionId: String, entityType: String, entityId: String) extends TransactionEvent

  sealed trait TransactionState {
    def transactionId: String
  }

  final case class EmptyState(transactionId: String) extends TransactionState

  final case class EntityTypeAndId(entityType: String, entityId: String)

  final case class PendingState(
    transactionId: String,
    sequence: Int = 0,
    outOfSequence: Boolean = false,
    sent: Seq[EntityTypeAndId] = Nil,
    confirmed: Seq[EntityTypeAndId] = Nil,
    exceptions: Seq[EntityTypeAndId] = Nil
  ) extends TransactionState {

    def withCommandAdded(commandAdded: CommandAdded): PendingState =
      if (validSequence(commandAdded.sequence))
        copy(sequence = commandAdded.sequence)
      else
        copy(sequence = commandAdded.sequence, outOfSequence = true)

    def validSequence(sequence: Long): Boolean =
      if (this.sequence == 0 && sequence == 1 || (this.sequence - sequence == 1))
        true
      else false

    private def canCommit(): Boolean =
      if (sent.size == (confirmed.size - exceptions.size)
        true
      else false
  }

  final case class CommitState(
    transactionId: String,
    sent: Seq[EntityTypeAndId] = Nil,
    confirmed: Seq[EntityTypeAndId] = Nil) extends TransactionState {

    def canComplete(): Boolean =
      if (sent.size == confirmed.size)
        true
      else false
  }

  final case class RollbackState(
    transactionId: String,
    entityCount: Int,
    rollbackConfirmedCount: Int = 0) extends TransactionState {

    def canComplete: Boolean =
      if (rollbackConfirmedCount == entityCount)
        true
      else false
  }

  def apply(transactionId: String, entityLocator: EntityLocator): Behavior[TransactionCommand] =
    Behaviors.setup { context =>
      EventSourcedBehavior[TransactionCommand, TransactionEvent, TransactionState](
        persistenceId = PersistenceId.ofUniqueId(transactionId),
        emptyState = EmptyState(transactionId),
        commandHandler = commandHandler(entityLocator, context.log),
        eventHandler = eventHandler(context.log))
    }

  private def commandHandler(entityLocator: EntityLocator, log: Logger): (TransactionState, TransactionCommand) =>
                     Effect[TransactionEvent, TransactionState] = { (state, command) =>
    state match {
      case EmptyState(_) =>
        command match {
          case StartTransaction(transactionId, description) =>
            log.debug(s"Starting transaction $transactionId")
            Effect.persist(TransactionStarted(transactionId, description))
        }
      case pending @ PendingState(transactionId, _, _, _, _) =>
        command match {
          case AddCommand(transactionId, command, sequence) =>
            log.debug(s"Adding command to ${command.entityType}::${command.entityId} to transaction $transactionId")
            Effect.persist(CommandAdded(transactionId, command, sequence))
              .thenRun( _ =>
                if (pending.validSequence(sequence))
                  entityLocator.sendCommand(command)
                else {
                  log.error(s"Out of sequence error for transaction $transactionId, sequence numbers must start with 1 and be contiguous")
                  Effect.persist(TransactionErrorCondition(transactionId))
                }
              )
          case EndCommands(transactionId, sequence) =>
            log.debug(s"Ending transaction $transactionId")
            Effect.persist(CommandsEnded(transactionId, sequence))
          case _: EntityTransactionStarted =>

          case ReportError(_, event) =>
            log.error(s"Error <${event.error}> occurred on entity ${event.entityType}::${event.entityId} for transaction $transactionId")
            Effect.persist(TransactionErrorCondition(transactionId))
        }
      case CommitState(transactionId, _, _) =>
        command match {
          case RecordClearing(_, EntityTransactionCleared(_, entityType, entityId)) =>
            Effect.persist(TransactionCleared(transactionId, entityType, entityId))
        }
      case RollbackState(transactionId, _, _) =>
        command match {
          case RecordReversal(_, EntityTransactionReversed(_, entityType, entityId)) =>
            Effect.persist(TransactionReversed(transactionId, entityType, entityId))
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
      case pending @ PendingState(transactionId, _, _, _, _) =>
        event match {
          case commandAdded @ CommandAdded(transactionId, command, _) =>
            log.info(s"Added command to ${command.entityType}::${command.entityId} to transaction $transactionId")
            pending.withCommandAdded(commandAdded)
          case _: TransactionErrorCondition =>
            RollbackState(transactionId, pending.sequence)
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
