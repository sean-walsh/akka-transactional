package com.lightbend.transactional

import com.lightbend.transactional.lightbend.{EntityId, TransactionId}

/**
  * Wrapping "Envelope" commands to be handled by entities participating in a saga.
  */
object PersistentSagaActorCommands {

  /** Commands sent to a saga. **/

  sealed trait PersistentSagaActorCommand
  // Adds new command upon and entity participating in a transaction.
  case class StartSaga(transactionId: TransactionId, description: String, commands: Seq[TransactionalCommand])
    extends PersistentSagaActorCommand
  case class AddSagaCommand(transactionId: TransactionId, command: TransactionalCommand) extends PersistentSagaActorCommand
  case class StartStreamingSaga(transactionId: TransactionId, description: String, initialCommand: AddSagaCommand)
    extends PersistentSagaActorCommand
  case class EndStreamingSaga(transactionId: TransactionId)

  /** Commands sent to entities **/

  // Trait for any entity commands participating in a saga.
  trait TransactionalCommand {
    def entityId: EntityId
    def shardRegion: String
  }

  // Transactional command wrappers.
  sealed trait TransactionalCommandWrapper {
    def transactionId: String
    def entityId: EntityId
  }

  case class StartTransaction(transactionId: TransactionId, entityId: EntityId, eventTag: String,
                              command: TransactionalCommand) extends TransactionalCommandWrapper
  case class CommitTransaction(transactionId: TransactionId, entityId: EntityId, eventTag: String)
    extends TransactionalCommandWrapper
  case class RollbackTransaction(transactionId: TransactionId, entityId: EntityId, eventTag: String)
    extends TransactionalCommandWrapper
}
