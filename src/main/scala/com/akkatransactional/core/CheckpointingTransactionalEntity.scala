package com.akkatransactional.core

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import org.slf4j.Logger
import TransactionalEntityCommands._
import TransactionalEntityEvents._
import akka.Done

/**
  * Abstraction around shards, pubsub, etc.
  */
trait EntityLocator {
  def sendCommand(command: TransactionalCommandEnvelope): Unit
}

/**
  * The transactional actor that lives through the entire process until completion.
  */
object CheckPointingTransactionalEntity {
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
      command match {
        case GetCurrentState(transactionId, replyTo) =>
          replyTo ! ReportedTransactionState(transactionId, state.getClass.getSimpleName)
          Effect.none
        case _ =>
          state match {
            case EmptyState(_) =>
              command match {
                case StartTransaction(_, description, replyTo) =>
                  log.debug(s"Starting transaction $transactionId")

                  Effect.persist(TransactionStarted(transactionId, description))
                    .thenReply(replyTo)(_ => Done)
                case unexpected =>
                  log.error(s"Received unexpected command $unexpected, expected StartTransaction")
                  Effect.none
              }
            case pending: PendingState =>
              command match {
                case AddCommand(transactionId, entityType, entityId, command, sequence, replyTo) =>
                  log.debug(s"Adding command to $entityType::$entityId to transaction $transactionId")

                  Effect.persist(CommandAdded(transactionId, entityType, entityId, command, sequence))
                    .thenRun( _ =>
                      if (pending.isValidSequence(sequence)) {
                        pending.sendStartTransaction(entityLocator, entityType, entityId, command)
                        replyTo ! Done
                      } else {
                        log.error(s"Out of sequence error for transaction $transactionId, sequence numbers must start with 1 and be contiguous")
                        Effect.persist(TransactionError(transactionId))
                      }
                    )
                case EndCommands(_, sequence, replyTo) =>
                  log.debug(s"Ending transaction $transactionId")

                  Effect.persist(CommandsEnded(transactionId, sequence))
                    .thenRun { _ =>
                      val proposed = pending.copy(commandsEnded = true, outOfSequenceError = pending.isValidSequence(sequence))
                      proposed.sendSideEffectsMaybe(entityLocator)
                      replyTo ! Done
                    }
                case ConfirmEntityEvent(_, entityEvent) =>
                  log.debug(s"Received $entityEvent for transaction $transactionId")
                  val confirmed = EntityEventConfirmed(transactionId, entityEvent)

                  Effect.persist(confirmed).thenRun { _ =>
                    val proposed = pending.withEntityEventConfirmed(confirmed)
                    proposed.sendSideEffectsMaybe(entityLocator)
                  }
                case ReportEntityError(_, entityEvent) =>
                  log.error(s"Received error $entityEvent for transaction $transactionId")
                  val error = EntityErrorReported(transactionId, entityEvent)

                  Effect.persist(error).thenRun { _ =>
                    val proposed = pending.withEntityErrorConfirmed(entityEvent)
                    proposed.sendSideEffectsMaybe(entityLocator)
                  }
                case unexpected =>
                  log.error(s"Received unexpected command $unexpected")
                  Effect.none
              }
            case committing @ CommitState(transactionId, _, _) =>
              command match {
                case ConfirmEntityClearing(_, event) =>
                  if (!committing.hasConfirmed(EntityTypeAndId(event.entityType, event.entityId)))
                    Effect.persist(EntityClearingConfirmed(transactionId, event))
                  else {
                    log.warn(s"Duplicate clearing confirmation:$event")
                    Effect.none
                  }
                case unexpected =>
                  log.error(s"Received unexpected command $unexpected, expected ConfirmEntityClearing")
                  Effect.none
              }
            case RollbackState(transactionId, _, _) =>
              command match {
                case ConfirmEntityReversal(_, event) =>
                  Effect.persist(EntityReversalConfirmed(transactionId, event))
                case unexpected =>
                  log.error(s"Received unexpected command $unexpected, expected ConfirmEntityReversal")
                  Effect.none
              }
            case unexpected =>
              log.error(s"Received unexpected command $unexpected, expected ConfirmEntityReversal")
              Effect.none
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
      case pending: PendingState =>
        event match {
          case added @ CommandAdded(_, entityType, entityId, _, _) =>
            log.info(s"Added command to $entityType::$entityId to transaction ${state.transactionId}")
            pending.withCommandAdded(added)
          case confirmed @ EntityEventConfirmed(transactionId, _) =>
            log.info(s"Command confirmed to ${confirmed.entityEvent.entityType}::${confirmed.entityEvent.entityId} to transaction $transactionId")
            val withConfirmed = pending.withEntityEventConfirmed(confirmed)

            if (withConfirmed.isCommitCondition())
                CommitState(transactionId, pending.entities)
            else if (withConfirmed.isRollbackCondition())
                RollbackState(transactionId, pending.entities)
            else
                withConfirmed

          case ended: CommandsEnded =>
            // Will be one of pending, committing or rollback
            pending.withCommandsEnded(ended);

          case _: EntityErrorReported =>
            pending
            //todo
            //RollbackState(transactionId, pending.sequence)

          case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
        }
      case CommitState(_, _, _) =>
        event match {
          case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
        }
      case RollbackState(_, _, _) =>
        event match {
          case _ => throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
        }
      case _ =>
        throw new IllegalStateException(s"unexpected event [$event] in state [$state]")
    }
  }
}
