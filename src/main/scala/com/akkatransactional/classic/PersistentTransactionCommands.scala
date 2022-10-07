//package com.akkatransactional.classic
//
///**
//  * Wrapping "Envelope" commands to be handled by entities participating in a transaction.
//  */
//object PersistentTransactionCommands {
//
//  /** Commands sent to a persistent transaction. **/
//  trait PersistentTransactionCommand
//
//  case class AddStreamingCommand(transactionId: String, command: TransactionalCommand, sequence: Long)
//    extends PersistentTransactionCommand
//  case class StartTransaction(transactionId: String, description: String) extends PersistentTransactionCommand
//  case class EndStreamingCommands(transactionId: String, sequence: Long) extends PersistentTransactionCommand
//
//  /** Commands sent to entities **/
//  // Trait for any entity commands participating in a saga.
//  trait TransactionalCommand {
//    def entityId: String
//    def entityType: String // Maps to a shard region.
//  }
//
//  // Transactional command wrappers.
//  sealed trait TransactionalCommandWrapper {
//    def transactionId: String
//    def entityId: String
//  }
//
//  case class StartEntityTransaction(transactionId: String, entityId: String, command: TransactionalCommand)
//    extends TransactionalCommandWrapper
//  case class CommitTransaction(transactionId: String, entityId: String)
//    extends TransactionalCommandWrapper
//  case class RollbackTransaction(transactionId: String, entityId: String)
//    extends TransactionalCommandWrapper
//}
