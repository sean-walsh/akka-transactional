package com.lightbend.transactional

/**
  * Wrapping "Envelope" commands to be handled by entities participating in a saga.
  */
object PersistentSagaActorCommands {

  /** Commands sent to a saga. **/

  sealed trait PersistentSagaActorCommand
  case class StartSaga(transactionId: String, description: String, commands: Seq[TransactionalCommand])
    extends PersistentSagaActorCommand
  case class AddSagaCommand(transactionId: String, command: TransactionalCommand, sequence: Long)
    extends PersistentSagaActorCommand
  case class StartStreamingSaga(transactionId: String, description: String, initialCommand: AddSagaCommand)
    extends PersistentSagaActorCommand
  case class EndStreamingSaga(transactionId: String)

  /** Commands sent to entities **/

  // Trait for any entity commands participating in a saga.
  trait TransactionalCommand {
    def entityId: String
    def shardRegion: String
  }

  // Transactional command wrappers.
  sealed trait TransactionalCommandWrapper {
    def transactionId: String
    def entityId: String
  }

  case class StartTransaction(transactionId: String, entityId: String, eventTag: String,
                              command: TransactionalCommand) extends TransactionalCommandWrapper
  case class CommitTransaction(transactionId: String, entityId: String, eventTag: String)
    extends TransactionalCommandWrapper
  case class RollbackTransaction(transactionId: String, entityId: String, eventTag: String)
    extends TransactionalCommandWrapper
}
