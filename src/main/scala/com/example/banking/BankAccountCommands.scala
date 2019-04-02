package com.example.banking

import com.lightbend.transactional.PersistentTransactionCommands.TransactionalCommand

/**
  * Commands handled by a bank account.
  */
object BankAccountCommands {

  sealed trait BankAccountCommand {
    def accountNumber: String
  }

  trait BankAccountTransactionalCommand extends BankAccountCommand with TransactionalCommand {
    def amount: BigDecimal

    override val shardRegion = BankAccountActor.RegionName
  }

  case class CreateBankAccount(customerId: String, accountNumber: String) extends BankAccountCommand

  case class DepositFunds(accountNumber: String, amount: BigDecimal)
    extends BankAccountTransactionalCommand {
    override val entityId: String = accountNumber
  }

  case class WithdrawFunds(accountNumber: String, amount: BigDecimal)
    extends BankAccountTransactionalCommand {
    override val entityId: String = accountNumber
  }
}
