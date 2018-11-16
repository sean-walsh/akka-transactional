package com.example.bankaccount

import akka.actor.{ActorLogging, Props, Stash}
import akka.persistence.PersistentActor
import akka.persistence.journal.Tagged

/**
  * Bank account companion object.
  */
case object BankAccountActor {

  sealed trait State
  case object Uninitialized extends State
  case object Active extends State
  case object InTransaction extends State

  val PersistenceIdPrefix = "BankAccount|"
  val EntityPrefix = "bank-account-"

  /**
    * The state of a bank account in time. This may be made private, but for convenience in testing I left it
    * sharable via asking the actor.
    * @param currentState the current transactional state.
    * @param balance the actual balance of the bank account.
    * @param pendingBalance the balance that reflects any pending transaction.
    */
  case class BankAccountState(
    accountNumber: AccountNumber,
    currentState: State,
    balance: BigDecimal,
    pendingBalance: BigDecimal)

  /**
    * Factory method for BankAccount actor.
    * @return Props
    */
  def props(): Props = Props(new BankAccountActor)
}

/**
  * I am a bank account modeled as persistent actor.
  * This entity participates in a transactional saga. If desired, it can be enhanced to function outside of a saga
  * as well, in fact I'll do that when I get around to it.
  */
class BankAccountActor extends PersistentActor with ActorLogging with Stash {

  import BankAccountActor._
  import BankAccountCommands._
  import BankAccountEvents._
  import com.example.PersistentSagaActor._

  override def persistenceId: String = self.path.name

  private var state: BankAccountState = BankAccountState(self.path.name, Uninitialized, 0, 0)

  override def receiveCommand: Receive = default.orElse(stateReporting)

  /**
    * Here though the actor is instantiated, it is awaiting its first domain creation command to make it an
    * actual bank account.
    */
  def default: Receive = {
    case CreateBankAccount(customerId, accountNumber) =>
      persist(BankAccountCreated(customerId, accountNumber)) { event =>
        log.info(s"Creating BankAccount for customer $customerId with account number $accountNumber")
        applyEvent(event)
      }
  }

  /**
    * In the active state I am ready for a new transaction. This can be modified to handle non-transactional
    * behavior in addition if appropriate.
    */
  def active: Receive = {
    case StartTransaction(transactionId, cmd) =>
      cmd match {
        case DepositFunds(accountNumber, amount) =>
          persist(Tagged(TransactionStarted(transactionId, accountNumber, FundsDeposited(accountNumber, amount)),
            Set(transactionId))) { tagged =>
              applyEvent(tagged.payload.asInstanceOf[TransactionStarted])
          }
        case WithdrawFunds(accountNumber, amount) =>
          if (state.balance - amount >= 0)
            persist(Tagged(TransactionStarted(transactionId, accountNumber, FundsWithdrawn(accountNumber, amount)),
              Set(transactionId))) { tagged =>
                applyEvent(tagged.payload.asInstanceOf[TransactionStarted])
            }
          else {
            persist(Tagged(TransactionStarted(transactionId, accountNumber,
              InsufficientFunds(accountNumber, state.balance, amount)), Set(transactionId))) { tagged =>
                applyEvent(tagged.payload.asInstanceOf[TransactionStarted])
              }
          }
      }
  }

  /**
    * When in a transaction I can only handle commits and rollbacs.
    * @param processing TransactionalEvent the event that was the start of this transaction.
    */
  def inTransaction(processing: TransactionalEvent): Receive = {
    case CommitTransaction(transactionId, entityId) =>
      persist(Tagged(TransactionCleared(transactionId, entityId, processing), Set(transactionId))) { tagged =>
        applyEvent(tagged.payload.asInstanceOf[TransactionCleared])
      }

    case RollbackTransaction(transactionId, entityId) =>
      persist(Tagged(TransactionReversed(transactionId, entityId, processing), Set(transactionId))) { tagged =>
        applyEvent(tagged.payload.asInstanceOf[TransactionReversed])
      }
    case CompleteTransaction(transactionId, entityId) =>
      persist(Tagged(TransactionComplete(transactionId, entityId, processing), Set(transactionId))) { tagged =>
        applyEvent(tagged.payload.asInstanceOf[TransactionComplete])
      }
  }

  /**
    * Report current state for ease of testing.
    */
  def stateReporting: Receive = {
    case GetBankAccountState => sender() ! state
  }

  /**
    * Apply BankAccountCreated event.
    */
  def applyEvent(event: BankAccountCreated): Unit = {
    state = state.copy(currentState = Active)
    context.become(active.orElse(stateReporting))
    unstashAll()
  }

  /**
    * Apply TransactionStarted event envelope.
    */
  def applyEvent(envelope: TransactionStarted): Unit = {
    envelope.event match {
      case _: BankAccountTransactionalEvent =>
        val amount = envelope.event.asInstanceOf[BankAccountTransactionalEvent].amount

        envelope.event match {
          case _: FundsDeposited =>
            state = state.copy(currentState = InTransaction, pendingBalance = state.balance + amount)
          case _: FundsWithdrawn =>
            state = state.copy(currentState = InTransaction, pendingBalance = state.balance - amount)
        }
      case _: BankAccountTransactionalExceptionEvent =>
        state.copy(currentState = InTransaction)
    }

    context.become(inTransaction(envelope.event).orElse(stateReporting).orElse { case _ => stash })
  }

  /**
    * Apply TransactionCleared event envelope.
    */
  def applyEvent(envelope: TransactionCleared): Unit = {
    state = state.copy(currentState = Active, balance = state.pendingBalance, pendingBalance = 0)
    context.become(active.orElse(stateReporting))
    unstashAll()
  }

  /**
    * Apply TransactionReversed event envelope.
    */
  def applyEvent(envelope: TransactionReversed): Unit = {
    state = state.copy(currentState = Active, pendingBalance = 0)
    context.become(active.orElse(stateReporting))
    unstashAll()
  }

  /**
    * Apply TransactionComplete event envelope.
    */
  def applyEvent(event: TransactionComplete): Unit = {
    state = state.copy(currentState = Active, pendingBalance = 0)
    context.become(active.orElse(stateReporting))
    unstashAll()
  }

  override def receiveRecover: Receive = {
    case created: BankAccountCreated => applyEvent(created)
    case started: TransactionStarted => applyEvent(started)
    case cleared: TransactionCleared => applyEvent(cleared)
    case reversed: TransactionReversed => applyEvent(reversed)
    case complete: TransactionComplete => applyEvent(complete)
  }
}
