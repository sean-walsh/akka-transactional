package com.akkatransactional.typed

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.Effect
import org.slf4j.Logger

object TransactionalEntityProtocol {
  // Trait for any entity commands participating in a transaction.
  trait EntityCommand {
    def entityId: String
    def entityType: String // Maps to a shard region.
  }
}

object TransactionalActor {

  import TransactionalEntityProtocol._

  sealed trait TransactionCommand
  final case class AddCommand(transactionId: String, command: EntityCommand, sequence: Long)
    extends TransactionCommand
  final case class StartTransaction(transactionId: String, description: String) extends TransactionCommand
  final case class EndTransaction(transactionId: String, sequence: Long) extends TransactionCommand

  trait TransactionEvent {
    def transactionId: String
    def entityId: String
  }

  case class TransactionStarted(transactionId: String, description: String) extends TransactionEvent {
    override val entityId = transactionId
  }

  case class CommandAdded(transactionId: String, command: EntityCommand, sequence: Long)
    extends TransactionEvent {
    override val entityId = transactionId
  }

  case class PersistentTransactionComplete(transactionId: String) extends TransactionEvent {
    override val entityId = transactionId
  }

  case class TransactionEnded(transactionId: String, sequence: Long) extends TransactionEvent {
    override val entityId = transactionId
  }

  sealed trait TransactionState {
    def transactionId: String
  }

  final case class EmptyState(transactionId: String) extends TransactionState

  final case class PendingState(
    transactionId: String,
    sequence: Long = 0L,
    pendingConfirmed: Seq[String] = Seq.empty
  ) extends TransactionState

  final case class CommitState(
    transactionId: String,
    commitConfirmed: Seq[String] = Seq.empty) extends TransactionState

  final case class RollbackState(
    transactionId: String
    , rollbackConfirmed: Seq[String] = Seq.empty) extends TransactionState

  def apply(transactionId: String, shardRegions: Map[String, ActorRef[_]]): Behavior[TransactionCommand] =
    Behaviors.setup { context =>
      EventSourcedBehavior[TransactionCommand, TransactionEvent, TransactionState](
        persistenceId = PersistenceId.ofUniqueId(transactionId),
        emptyState = EmptyState(transactionId),
        commandHandler = commandHandler(shardRegions, context.log),
        eventHandler = eventHandler(context.log))
    }

  private def commandHandler(shardRegions: Map[String, ActorRef[EntityCommand]], log: Logger): (TransactionState, TransactionCommand) =>
                     Effect[TransactionEvent, TransactionState] = { (state, command) =>
    state match {
      case EmptyState(_) =>
        command match {
          case StartTransaction(transactionId, description) =>
            Effect.persist(TransactionStarted(transactionId, description))
        }
      case PendingState(_, _, _) =>
        command match {
          case AddCommand(transactionId, command, sequence) =>
            Effect.persist(CommandAdded(transactionId, command, sequence)).thenRun {
              shardRegions.get(command.entityType) match {
                case Some(r) => r ! command
                case None => log.error(s"Unkown entity type:${command.entityType}")
              }
            }
        }
    }
  }

  private def eventHandler(log: Logger): (TransactionState, TransactionEvent) => TransactionState = { (state, event) =>
    state match {
      case EmptyState(_) =>
        event match {
          case TransactionStarted(transactionId, _) =>
            PendingState(transactionId)
          case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
        }
      case pending: PendingState =>
        event match {
          case CommandAdded(transactionId, command, sequence) =>
            pending.copy(sequence = sequence)
          case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
        }
    }
  }
}
