//package com.example.banking.classic
//
//import akka.actor.Props
//import akka.persistence.journal.Tagged
//import com.akkatransactional.classic.PersistentTransactionalActor.Ack
//import com.akkatransactional.classic.PersistentTransactionCommands._
//import com.akkatransactional.classic.PersistentTransactionEvents._
//
///**
//  * Bank account companion object.
//  */
//case object BankAccountActor {
//
//  final val EntityPrefix = "bank-account-"
//
//  final val RegionName = "bank-account-region"
//
//  // To query bank account balance.
//  case class Balance(pendingBalance: BigDecimal, balance: BigDecimal)
//  case class GetBalance(accountNumber: String)
//
//  /**
//    * Factory method for BankAccount actor.
//    */
//  def props: Props = Props(new BankAccountActor)
//}
//
///**
//  * I am a bank account modeled as persistent actor.
//  * This entity participates in a transaction. If desired, it can be enhanced to function outside of a transaction
//  * as well, in fact I'll do that when I get around to it.
//  */
//class BankAccountActor extends TransactionalEntity {
//
//  import BankAccountActor._
//  import BankAccountCommands._
//  import BankAccountEvents._
//
//  private case class BankAccountState(
//    balance: BigDecimal,
//    pendingBalance: BigDecimal)
//
//  override def persistenceId: String = self.path.name
//
//  private var state: BankAccountState = BankAccountState(0, 0)
//
//  override def receiveCommand: Receive = default
//
//  /**
//    * Here though the actor is instantiated, it is awaiting its first domain creation command to make it an
//    * actual bank account.
//    */
//  def default: Receive = {
//    case CreateBankAccount(customerId, accountNumber) =>
//      persist(BankAccountCreated(customerId, accountNumber)) { _ =>
//        log.info(s"Creating BankAccount for customer $customerId with account number $accountNumber")
//        context.become(active)
//        sender() ! Ack
//      }
//  }
//
//  /**
//    * In the active state I am ready for a new transaction. This can be modified to handle non-transactional
//    * behavior in addition if appropriate, just make sure to use stash.
//    */
//  override def active: Receive = {
//    case StartEntityTransaction(transactionId, _, cmd) =>
//      cmd match {
//        case DepositFunds(accountNumber, amount) =>
//          val started = EntityTransactionStarted(transactionId, accountNumber, FundsDeposited(accountNumber, amount))
//          persist(Tagged(started, Set(transactionId))) { _ =>
//            onTransactionStarted(started)
//          }
//        case WithdrawFunds(accountNumber, amount) =>
//          if (state.balance - amount >= 0) {
//            val started = EntityTransactionStarted(transactionId, accountNumber, FundsWithdrawn(accountNumber, amount))
//            persist(Tagged(started, Set(transactionId))) { _ =>
//              onTransactionStarted(started)
//            }
//          }
//          else {
//            val started = EntityTransactionStarted(transactionId, accountNumber, InsufficientFunds(accountNumber,
//              state.balance, amount))
//            persist(Tagged(started, Set(transactionId))) { _ =>
//              onTransactionStarted(started)
//            }
//          }
//      }
//    case _: GetBalance => sender() ! Balance(state.pendingBalance, state.balance)
//  }
//
//  override def applyTransactionStarted(started: EntityTransactionStarted): Unit =
//    started.event match {
//      case _: BankAccountTransactionalEvent =>
//        val amount = started.event.asInstanceOf[BankAccountTransactionalEvent].amount
//        started.event match {
//          case _: FundsDeposited =>
//            state = state.copy(pendingBalance = state.balance + amount)
//          case _: FundsWithdrawn =>
//            state = state.copy(pendingBalance = state.balance - amount)
//        }
//    }
//
//  override def applyTransactionCleared(cleared: TransactionCleared): Unit =
//    state = state.copy(balance = state.pendingBalance, pendingBalance = 0)
//
//  override def applyTransactionReversed(reversed: TransactionReversed): Unit =
//    state = state.copy(pendingBalance = 0)
//
//  override def applyTransactionException(exception: TransactionalExceptionEvent): Unit = {
//    // no state change needed here.
//  }
//
//  override def receiveRecover: Receive = {
//    case _: BankAccountCreated =>
//      context.become(active)
//    case envelope: TransactionalEventEnvelope => recover(envelope)
//  }
//}
