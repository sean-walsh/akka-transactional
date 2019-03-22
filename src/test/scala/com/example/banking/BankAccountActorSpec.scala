package com.example.banking

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props, Terminated}
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.example.banking.BankAccountActor.{Balance, GetBalance}
import com.example.banking.bankaccount.AccountNumber
import com.lightbend.transactional.PersistentSagaActor
import com.lightbend.transactional.PersistentSagaActorCommands._
import com.lightbend.transactional.PersistentSagaActorEvents._
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

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

class BankAccountActorSpec extends TestKit(ActorSystem("BankAccountSpec", ConfigFactory.parseString(BankAccountActorSpec.Config)))
  with WordSpecLike with Matchers with ImplicitSender with BeforeAndAfterAll {

  import BankAccountCommands._
  import BankAccountEvents._

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val timeout = Timeout(5.seconds)
  val OriginalTransactionId = "transactionId1"
  val SecondTransactionId = "transactionId2"
  val ThirdTransactionId = "transactionId3"

  // Mock saga actor for transaction 1
  system.actorOf(Props(new Actor {
    override def receive: Receive = {
      case EventConfirmationSentToSaga(deliveryId, transactionId, _) if transactionId == OriginalTransactionId =>
        sender() ! SagaDeliveryReceipt(deliveryId)
    }
  }),s"${PersistentSagaActor.EntityPrefix}$OriginalTransactionId")

  // Mock saga actor for transaction 2
  system.actorOf(Props(new Actor {
    override def receive: Receive = {
      case EventConfirmationSentToSaga(deliveryId, transactionId, _) if transactionId == SecondTransactionId =>
        sender() ! SagaDeliveryReceipt(deliveryId)
    }
  }),s"${PersistentSagaActor.EntityPrefix}$SecondTransactionId")

  // Mock saga actor for transaction 3
  system.actorOf(Props(new Actor {
    override def receive: Receive = {
      case EventConfirmationSentToSaga(deliveryId, transactionId, _) if transactionId == ThirdTransactionId =>
        sender() ! SagaDeliveryReceipt(deliveryId)
    }
  }),s"${PersistentSagaActor.EntityPrefix}$ThirdTransactionId")

  "a BankAccount" should {

    val CustomerNumber: String = "customerNumber"
    val AccountNumber: AccountNumber = "accountNumber1"
    val persistenceId: String = BankAccountActor.EntityPrefix + AccountNumber
    val bankAccount: ActorRef = system.actorOf(BankAccountActor.props, persistenceId)

    implicit val mat = ActorMaterializer()(system)
    val readJournal = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

    import scala.collection.mutable.ListBuffer

    "properly initialize with CreateBankAccount command" in {
      bankAccount ! CreateBankAccount(CustomerNumber, AccountNumber)

      val events: ListBuffer[Any] = new ListBuffer[Any]()
      readJournal.eventsByPersistenceId(persistenceId, 0L, Long.MaxValue).map(_.event).runForeach {
        case x => events += x
      }

      val ExpectedEvents = ListBuffer(BankAccountCreated(CustomerNumber, AccountNumber))
      awaitCond(events == ExpectedEvents, timeout.duration, 100.milliseconds, s"Expected events of $ExpectedEvents not received.")
    }

    "accept pending DepositFunds command and transition to inTransaction state" in {
      val Deposit = DepositFunds(AccountNumber, BigDecimal.valueOf(10))
      val deposited = FundsDeposited(Deposit.accountNumber, Deposit.amount)
      val cmd = StartTransaction(OriginalTransactionId, Deposit)
      bankAccount ! cmd

      val events: ListBuffer[Any] = new ListBuffer[Any]()
      readJournal.eventsByPersistenceId(persistenceId, 2L, Long.MaxValue).map(_.event).runForeach {
        case x => events += x
      }

      val ExpectedEvents = List(
        TransactionStarted(OriginalTransactionId, AccountNumber, deposited),
        EventConfirmationSentToSaga(1L, OriginalTransactionId, TransactionStarted(OriginalTransactionId, AccountNumber, deposited)),
        EventConfirmedReceipt(SagaDeliveryReceipt(1L), TransactionStarted(OriginalTransactionId, AccountNumber, deposited))
      )

      awaitCond(events == ExpectedEvents, timeout.duration, 100.milliseconds, s"Expected events of $ExpectedEvents not received.")
    }

    "stash pending WithdrawFunds command while inTransaction state" in {
      val OriginalAmount = BigDecimal.valueOf(10)
      val Withdrawal = WithdrawFunds(AccountNumber, BigDecimal.valueOf(5))
      val cmd = StartTransaction(SecondTransactionId, Withdrawal)
      bankAccount ! cmd

      val events: ListBuffer[Any] = new ListBuffer[Any]()
      readJournal.eventsByPersistenceId(persistenceId, 4L, Long.MaxValue).map(_.event).runForeach {
        case x => events += x
      }

      val ExpectedEvents = List(
        EventConfirmedReceipt(SagaDeliveryReceipt(1L),
        TransactionStarted(OriginalTransactionId, AccountNumber, FundsDeposited(AccountNumber, OriginalAmount)))
      )

      awaitCond(events == ExpectedEvents, timeout.duration, 100.milliseconds, s"Expected events of $ExpectedEvents not received.")
    }

    "accept commit of DepositFunds for first transaction and transition back to inTransaction state to handle " +
      "stashed Pending(WithdrawFunds)" in {
      val Amount = BigDecimal.valueOf(5)
      val cmd = CommitTransaction(OriginalTransactionId, AccountNumber)
      bankAccount ! cmd

      val events: ListBuffer[Any] = new ListBuffer[Any]()
      readJournal.eventsByPersistenceId(persistenceId, 5L, Long.MaxValue).map(_.event).runForeach {
        case x => events += x
      }

      val ExpectedEvents = List(
        TransactionCleared(OriginalTransactionId, AccountNumber),
        EventConfirmationSentToSaga(2L, OriginalTransactionId, TransactionCleared(OriginalTransactionId, AccountNumber)),
        EventConfirmedReceipt(SagaDeliveryReceipt(2), TransactionCleared(OriginalTransactionId, AccountNumber)),
        TransactionStarted(SecondTransactionId, AccountNumber, FundsWithdrawn(AccountNumber, 5)),
        EventConfirmationSentToSaga(3L, SecondTransactionId, TransactionStarted(SecondTransactionId, AccountNumber, FundsWithdrawn(AccountNumber, Amount))),
        EventConfirmedReceipt(SagaDeliveryReceipt(3L), TransactionStarted(SecondTransactionId, AccountNumber, FundsWithdrawn(AccountNumber, Amount)))
      )

      awaitCond(events == ExpectedEvents, timeout.duration, 100.milliseconds, s"Expected events of $ExpectedEvents not received.")
    }

    "accept commit of previously stashed WithdrawFunds and transition back to active state" in {
      val cmd = CommitTransaction(SecondTransactionId, AccountNumber)
      bankAccount ! cmd

      val events: ListBuffer[Any] = new ListBuffer[Any]()
      readJournal.eventsByPersistenceId(persistenceId, 11L, Long.MaxValue).map(_.event).runForeach {
        case x => events += x
      }

      val ExpectedEvents = List(
        TransactionCleared(SecondTransactionId, AccountNumber),
        EventConfirmationSentToSaga(4L, SecondTransactionId, TransactionCleared(SecondTransactionId, AccountNumber)),
        EventConfirmedReceipt(SagaDeliveryReceipt(4L), TransactionCleared(SecondTransactionId, AccountNumber)),
      )

      awaitCond(events == ExpectedEvents, timeout.duration, 100.milliseconds, s"Expected events of $ExpectedEvents not received.")
    }

    "start another transaction in order to rollback" in {
      val Deposit = DepositFunds(AccountNumber, BigDecimal.valueOf(1))
      val deposited = FundsDeposited(Deposit.accountNumber, Deposit.amount)
      val cmd = StartTransaction(ThirdTransactionId, Deposit)
      bankAccount ! cmd

      val events: ListBuffer[Any] = new ListBuffer[Any]()
      readJournal.eventsByPersistenceId(persistenceId, 14L, Long.MaxValue).map(_.event).runForeach {
        case x => events += x
      }

      val ExpectedEvents = List(
        TransactionStarted(ThirdTransactionId, AccountNumber, deposited),
        EventConfirmationSentToSaga(5L, ThirdTransactionId, TransactionStarted(ThirdTransactionId, AccountNumber, deposited)),
        EventConfirmedReceipt(SagaDeliveryReceipt(5L), TransactionStarted(ThirdTransactionId, AccountNumber, deposited))
      )

      awaitCond(events == ExpectedEvents, timeout.duration, 100.milliseconds, s"Expected events of $ExpectedEvents not received.")
    }

    "properly handle a rollback on third transaction" in {
      val cmd = RollbackTransaction(ThirdTransactionId, AccountNumber)
      bankAccount ! cmd

      val events: ListBuffer[Any] = new ListBuffer[Any]()
      readJournal.eventsByPersistenceId(persistenceId, 17L, Long.MaxValue).map(_.event).runForeach {
        case x => events += x
      }

      val ExpectedEvents = List(
        TransactionReversed(ThirdTransactionId, AccountNumber),
        EventConfirmationSentToSaga(6L, ThirdTransactionId, TransactionReversed(ThirdTransactionId, AccountNumber)),
        EventConfirmedReceipt(SagaDeliveryReceipt(6L), TransactionReversed(ThirdTransactionId, AccountNumber))
      )

      awaitCond(events == ExpectedEvents, timeout.duration, 100.milliseconds, s"Expected events of $ExpectedEvents not received.")
    }

    "replay properly" in {
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
