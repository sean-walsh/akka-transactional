package com.lightbend.transactional

import com.lightbend.transactional.lightbend.{EntityId, TransactionId}

/**
  * Wrapping "Envelope" commands to be handled by entities participating in a saga.
  */
object PersistentSagaActorCommands {

  /** Commands sent to a saga. **/
  sealed trait PersistentSagaActorCommand
  case class StartSaga(transactionId: TransactionId, description: String, commands: Seq[TransactionalCommand])
    extends PersistentSagaActorCommand
  case object GetSagaState extends PersistentSagaActorCommand

  /** Commands sent to entities **/

  // Trait for any entity commands participating in a saga.
  trait TransactionalCommand {
    def entityId: EntityId
  }

  // Transactional command wrappers.
  sealed trait TransactionalCommandWrapper {
    def transactionId: String
    def entityId: EntityId
  }

  case class StartTransaction(transactionId: TransactionId, entityId: EntityId, command: TransactionalCommand) extends TransactionalCommandWrapper
  case class CommitTransaction(transactionId: TransactionId, entityId: EntityId) extends TransactionalCommandWrapper
  case class RollbackTransaction(transactionId: TransactionId, entityId: EntityId) extends TransactionalCommandWrapper
}
