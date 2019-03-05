package com.example.bankaccount

import com.example.PersistentSagaActorCommands.TransactionalCommand
import com.example._

/**
  * Commands handled by a bank account.
  */
object BankAccountCommands {

  sealed trait BankAccountCommand {
    def accountNumber: String
  }

  trait BankAccountTransactionalCommand extends BankAccountCommand with TransactionalCommand {
    def amount: BigDecimal
  }

  case class CreateBankAccount(customerId: String, accountNumber: AccountNumber) extends BankAccountCommand

  case class DepositFunds(accountNumber: AccountNumber, amount: BigDecimal)
    extends BankAccountTransactionalCommand {
    override val entityId: EntityId = accountNumber
  }

  case class WithdrawFunds(accountNumber: AccountNumber, amount: BigDecimal)
    extends BankAccountTransactionalCommand {
    override val entityId: EntityId = accountNumber
  }

  case class GetBankAccount(accountNumber: AccountNumber) extends BankAccountCommand

  case class GetBankAccountState(accountNumber: AccountNumber) extends BankAccountCommand
}
