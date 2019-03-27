//package com.example.banking
//
//import akka.actor.Props
//import com.example.banking.BankAccountAnomalyEvents.WithdrawalExceedsDailyLimit
//import com.example.banking.bankaccount.AccountNumber
//import com.lightbend.transactional.PersistentSagaActorCommands._
//import com.lightbend.transactional.PersistentSagaActorEvents._
//import com.lightbend.transactional.TransactionalEntity
//
///**
//  * Bank account anomaly companion object. When participating in a transaction, this
//  * will be able to cancel the transaction and cause a rollback due to an anomalous
//  * event.
//  */
//case object BankAccountAnomalyActor {
//
//  sealed trait State
//  case object Uninitialized extends State
//  case object Active extends State
//  case object InTransaction extends State
//
//  val PersistenceIdPrefix = "BankAccountAnomaly|"
//  val EntityPrefix = "bank-account-anomaly"
//
//  /**
//    * Factory method for BankAccountAnomaly actor.
//    */
//  def props: Props = Props(new BankAccountAnomalyActor)
//}
//
///**
//  * I am an anomaly actor on behalf of a bank account.
//  * This will evenly shard across the cluster, just as bank accounts due, but will only issue
//  * events that are anomalies, otherwise will be transient and go out of scope after a
//  * transaction.
//  */
//class BankAccountAnomalyActor extends TransactionalEntity {
//
//  import BankAccountCommands._
//
//  case class BankAccountAnomalyState(
//    accountNumber: AccountNumber)
//
//  override def persistenceId: String = self.path.name
//
//  private var state: BankAccountAnomalyState = BankAccountAnomalyState(self.path.name)
//
//  override def receiveCommand: Receive = active
//
//  private final val AccountsDailyWithdrawalLimit: BigDecimal = 10000
//
//  /**
//    * In the active state I am ready for a new transaction. This can be modified to handle non-transactional
//    * behavior in addition if appropriate, just make sure to use stash.
//    */
//  override def active: Receive = {
//    case StartTransaction(transactionId, _, cmd) =>
//      cmd match {
//        case DepositFunds(_, _) =>
//
//        case WithdrawFunds(accountNumber, amount) =>
//          if (amount > AccountsDailyWithdrawalLimit)
//            persist(TransactionStarted(transactionId, accountNumber,
//              WithdrawalExceedsDailyLimit(accountNumber, amount))) { started =>
//              onTransactionStartedPersist(started)
//            }
//      }
//  }
//
//  def inTransaction(processing: TransactionalEvent): Receive = {
//    case CommitTransaction(_, _) =>
//
//    case RollbackTransaction(_, _) =>
//  }
//
//  override def applyTransactionStarted(started: TransactionStarted): Unit = {}
//
//  override def applyTransactionCleared(cleared: TransactionCleared): Unit = {}
//
//  override def applyTransactionReversed(reversed: TransactionReversed): Unit = {}
//
//  override def applyTransactionException(exception: TransactionalExceptionEvent): Unit = {}
//
//  override def receiveRecover: Receive = {
//    // No recovery necessary as this simple implementation deals with a constant to make decisions.
//    case _ =>
//  }
//}
