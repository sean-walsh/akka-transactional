package com.example.bankaccount

import com.example.PersistentSagaActor.TransactionalEvent

object BankAccountEvents {

  case class BankAccountCreated(customerId: String, accountNumber: AccountNumber) extends BankAccountEvent

  case class FundsDeposited(accountNumber: AccountNumber, amount: BigDecimal) extends BankAccountTransactionalEvent

  case class FundsWithdrawn(accountNumber: AccountNumber, amount: BigDecimal) extends BankAccountTransactionalEvent

  case class InsufficientFunds(accountNumber: AccountNumber, balance: BigDecimal, attemptedWithdrawal: BigDecimal)
    extends BankAccountTransactionalExceptionEvent

  // FIXME use more `sealed trait` to benefit from compiler finding missing cases in pattern match
  trait BankAccountEvent {
    def accountNumber: AccountNumber
  }

  trait BankAccountTransactionalEvent extends BankAccountEvent with TransactionalEvent {
    def amount: BigDecimal
  }

  trait BankAccountTransactionalExceptionEvent extends BankAccountEvent with TransactionalEvent
}
