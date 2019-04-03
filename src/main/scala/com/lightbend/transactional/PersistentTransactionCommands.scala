package com.lightbend.transactional

/**
  * Wrapping "Envelope" commands to be handled by entities participating in a transaction.
  */
object PersistentTransactionCommands {

  /** Commands sent to a persistent transaction. **/
  sealed trait PersistentTransactionCommand
  trait StartTransaction extends PersistentTransactionCommand // Extend this per implementation.

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

  case class StartEntityTransaction(transactionId: String, entityId: String, eventTag: String,
                                    command: TransactionalCommand) extends TransactionalCommandWrapper
  case class CommitTransaction(transactionId: String, entityId: String, eventTag: String)
    extends TransactionalCommandWrapper
  case class RollbackTransaction(transactionId: String, entityId: String, eventTag: String)
    extends TransactionalCommandWrapper
}
