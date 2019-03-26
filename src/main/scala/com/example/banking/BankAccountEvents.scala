package com.example.banking

import com.example.banking.bankaccount.AccountNumber
import com.lightbend.transactional.PersistentSagaActorEvents.{TransactionalEvent, TransactionalExceptionEvent}

/**
  * Events issued by a bank account.
  */
object BankAccountEvents {

  case class BankAccountCreated(customerId: String, accountNumber: AccountNumber) extends BankAccountEvent

  case class FundsDeposited(accountNumber: AccountNumber, amount: BigDecimal) extends BankAccountTransactionalEvent

  case class FundsWithdrawn(accountNumber: AccountNumber, amount: BigDecimal) extends BankAccountTransactionalEvent

  case class InsufficientFunds(accountNumber: AccountNumber, balance: BigDecimal, attemptedWithdrawal: BigDecimal)
    extends BankAccountTransactionalExceptionEvent

  sealed trait BankAccountEvent {
    def accountNumber: AccountNumber
  }

  trait BankAccountTransactionalEvent extends BankAccountEvent with TransactionalEvent {
    def amount: BigDecimal
  }

  trait BankAccountTransactionalExceptionEvent extends TransactionalExceptionEvent with BankAccountEvent
}
