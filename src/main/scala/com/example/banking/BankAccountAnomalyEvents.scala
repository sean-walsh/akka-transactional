package com.example.banking

import com.example.banking.bankaccount.AccountNumber
import com.lightbend.transactional.PersistentSagaActorEvents.TransactionalExceptionEvent

/**
  * Events issued by a bank account anomaly.
  */
object BankAccountAnomalyEvents {

  case class WithdrawalExceedsDailyLimit(accountNumber: AccountNumber, attemptedWithdrawal: BigDecimal)
    extends BankAccountAnomalyExceptionEvent

  sealed trait BankAccountEvent {
    def accountNumber: AccountNumber
  }

  trait BankAccountAnomalyExceptionEvent extends TransactionalExceptionEvent with BankAccountEvent
}
