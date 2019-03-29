package com.example.banking.anomaly

import com.lightbend.transactional.PersistentSagaActorEvents.TransactionalExceptionEvent

/**
  * Events issued by a bank account anomaly.
  */
object BankAccountAnomalyEvents {

  case class WithdrawalExceedsDailyLimit(accountNumber: String, attemptedWithdrawal: BigDecimal)
    extends BankAccountAnomalyExceptionEvent

  sealed trait BankAccountEvent {
    def accountNumber: String
  }

  trait BankAccountAnomalyExceptionEvent extends TransactionalExceptionEvent with BankAccountEvent
}
