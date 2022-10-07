package com.akkatransactional.core

import akka.Done
import akka.actor.typed.ActorRef
import com.akkatransactional.CborSerializable
import com.akkatransactional.core.TransactionalEntityEvents._

object TransactionalEntityCommands {
  // To be extended for any participating entity's commands
  trait EntityTransactionalCommand extends CborSerializable {
    def entityId: String
  }

  // Commands to the transactional entity
  sealed trait TransactionCommand extends CborSerializable
  final case class AddCommand(transactionId: String, entityType: String, entityId: String,
                              command: EntityTransactionalCommand, sequence: Int, replyTo: ActorRef[Done])
    extends TransactionCommand
  final case class StartTransaction(transactionId: String, description: String, replyTo: ActorRef[Done])
    extends TransactionCommand
  final case class EndCommands(transactionId: String, sequence: Int, replyTo: ActorRef[Done]) extends TransactionCommand
  final case class ConfirmEntityEvent(transactionId: String, event: EntityPendingEvent) extends TransactionCommand
  final case class ReportEntityError(transactionId: String, event: EntityErrorEvent) extends TransactionCommand
  final case class ConfirmEntityClearing(transactionId: String, event: EntityClearingEvent) extends TransactionCommand
  final case class ConfirmEntityReversal(transactionId: String, event: EntityReversalEvent) extends TransactionCommand
  final case class GetCurrentState(transactionId: String, replyTo:ActorRef[ReportedTransactionState]) extends TransactionCommand

  // Current state for reporting/testing purposes
  final case class ReportedTransactionState(transactionId: String, currentState: String)
    extends CborSerializable

  // The contract from this transactional actor to the entities.
  sealed trait TransactionalCommandEnvelope extends CborSerializable {
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
}
