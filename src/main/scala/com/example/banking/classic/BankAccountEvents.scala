//package com.example.banking.classic
//
//import com.akkatransactional.classic.PersistentTransactionEvents.{TransactionalEvent, TransactionalExceptionEvent}
//
///**
//  * Events issued by a bank account.
//  */
//object BankAccountEvents {
//
//  case class BankAccountCreated(customerId: String, accountNumber: String) extends BankAccountEvent
//
//  case class FundsDeposited(accountNumber: String, amount: BigDecimal) extends BankAccountTransactionalEvent
//
//  case class FundsWithdrawn(accountNumber: String, amount: BigDecimal) extends BankAccountTransactionalEvent
//
//  case class InsufficientFunds(accountNumber: String, balance: BigDecimal, attemptedWithdrawal: BigDecimal)
//    extends BankAccountTransactionalExceptionEvent
//
//  sealed trait BankAccountEvent {
//    def accountNumber: String
//  }
//
//  trait BankAccountTransactionalEvent extends BankAccountEvent with TransactionalEvent {
//    def amount: BigDecimal
//  }
//
//  trait BankAccountTransactionalExceptionEvent extends TransactionalExceptionEvent with BankAccountEvent
//}
