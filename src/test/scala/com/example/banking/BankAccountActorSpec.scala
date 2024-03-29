package com.example.banking

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Terminated}
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.example.banking.BankAccountActor.{Balance, GetBalance}
import com.lightbend.transactional.PersistentTransactionCommands._
import com.lightbend.transactional.PersistentTransactionEvents._
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

object BankAccountActorSpec {

  val Config =
    """
      |akka.actor.provider = "local"
      |akka.actor.warn-about-java-serializer-usage = "false"
      |akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
      |akka.persistence.journal.leveldb.dir = "target/leveldb"
      |akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      |akka.persistence.snapshot-store.local.dir = "target/snapshots"
    """.stripMargin
}

class BankAccountActorSpec extends TestKit(ActorSystem("BankAccountSpec",
  ConfigFactory.parseString(BankAccountActorSpec.Config))) with WordSpecLike with Matchers
  with ImplicitSender with BeforeAndAfterAll {

  import BankAccountCommands._
  import BankAccountEvents._

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val timeout = Timeout(5.seconds)
  val OriginalTransactionId = "transactionId1"
  val SecondTransactionId = "transactionId2"
  val ThirdTransactionId = "transactionId3"

  "a BankAccount" should {

    val CustomerNumber: String = "customerNumber"
    val AccountNumber: String = "accountNumber1"
    val persistenceId: String = BankAccountActor.EntityPrefix + AccountNumber
    val bankAccount: ActorRef = system.actorOf(BankAccountActor.props, persistenceId)

    val readJournal = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)
    var events: ListBuffer[Any] = new ListBuffer[Any]()
    readJournal.eventsByPersistenceId(persistenceId, 1L, Long.MaxValue).map(_.event).runForeach {
      case x => events += x
    }(ActorMaterializer()(system))

    "properly initialize with CreateBankAccount command" in {
      bankAccount ! CreateBankAccount(CustomerNumber, AccountNumber)

      val ExpectedEvents = ListBuffer(BankAccountCreated(CustomerNumber, AccountNumber))
      awaitCond(events == ExpectedEvents, timeout.duration, 100.milliseconds,
        s"Expected events of $ExpectedEvents not received.")
    }

    "accept pending DepositFunds command and transition to inTransaction state" in {
      events.remove(events.size - 1)
      val Deposit = DepositFunds(AccountNumber, BigDecimal.valueOf(10))
      val deposited = FundsDeposited(Deposit.accountNumber, Deposit.amount)
      val cmd = StartEntityTransaction(OriginalTransactionId, Deposit.accountNumber, Deposit)
      bankAccount ! cmd

      val ExpectedEvents = List(
        EntityTransactionStarted(OriginalTransactionId, AccountNumber, deposited),
      )

      awaitCond(events == ExpectedEvents, timeout.duration, 100.milliseconds,
        s"Expected events of $ExpectedEvents not received.")
    }

    "accept commit of DepositFunds for first transaction and transition back to inTransaction state to handle " +
      "stashed Pending(WithdrawFunds)" in {

      events = new ListBuffer[Any]()
      val Withdrawal = WithdrawFunds(AccountNumber, BigDecimal.valueOf(5))
      val cmd1 = StartEntityTransaction(SecondTransactionId, Withdrawal.accountNumber, Withdrawal)
      bankAccount ! cmd1
      val cmd2 = CommitTransaction(OriginalTransactionId, AccountNumber)
      bankAccount ! cmd2

      val ExpectedEvents = List(
        TransactionCleared(OriginalTransactionId, AccountNumber, FundsDeposited(AccountNumber, 10)),
        EntityTransactionStarted(SecondTransactionId, AccountNumber, FundsWithdrawn(AccountNumber, 5)),
      )

      awaitCond(events == ExpectedEvents, timeout.duration, 100.milliseconds,
        s"Expected events of $ExpectedEvents not received.")
    }

    "accept commit of previously stashed WithdrawFunds and transition back to active state" in {
      events = new ListBuffer[Any]()
      val cmd = CommitTransaction(SecondTransactionId, AccountNumber)
      bankAccount ! cmd

      val ExpectedEvents = List(
        TransactionCleared(SecondTransactionId, AccountNumber, FundsWithdrawn(AccountNumber, 5))
      )

      awaitCond(events == ExpectedEvents, timeout.duration, 100.milliseconds,
        s"Expected events of $ExpectedEvents not received.")
    }

    "start another transaction in order to rollback" in {
      events = new ListBuffer[Any]()
      val Deposit = DepositFunds(AccountNumber, BigDecimal.valueOf(1))
      val cmd = StartEntityTransaction(ThirdTransactionId, Deposit.accountNumber, Deposit)
      bankAccount ! cmd

      val ExpectedEvents = List(
        EntityTransactionStarted(ThirdTransactionId, AccountNumber, FundsDeposited(Deposit.accountNumber, Deposit.amount))
      )

      awaitCond(events == ExpectedEvents, timeout.duration, 100.milliseconds,
        s"Expected events of $ExpectedEvents not received.")
    }

    "properly handle a rollback on third transaction" in {
      events = new ListBuffer[Any]()
      val cmd = RollbackTransaction(ThirdTransactionId, AccountNumber)
      bankAccount ! cmd

      val ExpectedEvents = List(
        TransactionReversed(ThirdTransactionId, AccountNumber, FundsDeposited(AccountNumber,
          BigDecimal.valueOf(1)))
      )

      awaitCond(events == ExpectedEvents, timeout.duration, 100.milliseconds,
        s"Expected events of $ExpectedEvents not received.")
    }

    "recover" in {
      val probe = TestProbe()
      probe.watch(bankAccount)
      bankAccount ! PoisonPill
      probe.expectMsgClass(classOf[Terminated])

      val bankAccount2 = system.actorOf(BankAccountActor.props, persistenceId)
      probe.send(bankAccount2, GetBalance(AccountNumber))
      probe.expectMsg(Balance(BigDecimal.valueOf(0), BigDecimal.valueOf(5))) // Deposit of 10 and withdrawal of 5.
    }
  }
}
