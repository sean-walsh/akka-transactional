package com.example.banking.classic

import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.akkatransactional.classic.PersistentTransactionCommands._
import com.akkatransactional.classic.PersistentTransactionalActor
import com.akkatransactional.classic.PersistentTransactionEvents._
import com.akkatransactional.classic.PersistentTransactionalActor.{GetTransactionState, TransactionState}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration._

object BankAccountTransactionSpec {

  val Config =
    """
      |akka.actor.provider = "local"
      |akka.actor.warn-about-java-serializer-usage = "false"
      |akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
      |akka.persistence.journal.leveldb.dir = "target/leveldb"
      |akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      |akka.persistence.snapshot-store.local.dir = "target/snapshots"
      |banking.bank-account.akka-transactional.transaction-type = "bank account transaction"
      |banking.bank-account.akka-transactional.retry-after = 5 minutes
      |banking.bank-account.akka-transactional.timeout-after = 5 minutes
    """.stripMargin
}

class BankAccountTransactionSpec extends TestKit(ActorSystem("BankAccountStreamingTransactionSpec",
  ConfigFactory.parseString(BankAccountTransactionSpec.Config))) with WordSpecLike with Matchers
  with ImplicitSender with BeforeAndAfterAll {

  import BankAccountCommands._
  import BankAccountEvents._

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val timeout = Timeout(10.seconds)

  "a BankAccount Streaming Transaction" should {
    // Bank account shard region mock.
    val bankAccountRegion = system.actorOf(Props(new Actor() {
      override def receive: Receive = {
        case cmd @ CreateBankAccount(_, accountNumber) =>
          system.actorSelection(s"/user/${BankAccountActor.EntityPrefix}$accountNumber") ! cmd
        case tcw: TransactionalCommandWrapper =>
          system.actorSelection(s"/user/${BankAccountActor.EntityPrefix}${tcw.entityId}") ! tcw
      }
    }), s"${BankAccountActor.RegionName}")

    // Transaction shard region mock.
    system.actorOf(Props(new Actor() {
      override def receive: Receive = {
        case envelope: TransactionalEventEnvelope =>
          system.actorSelection(s"/user/${PersistentTransactionalActor.EntityPrefix}${envelope.transactionId}") ! envelope
      }
    }), s"${PersistentTransactionalActor.RegionName}")

    // Instantiate the bank accounts (sharding would do this in clustered mode).
    val Account11: String = "accountNumber11"
    val Account22: String = "accountNumber22"

    system.actorOf(BankAccountActor.props, s"${BankAccountActor.EntityPrefix}$Account11")
    system.actorOf(BankAccountActor.props, s"${BankAccountActor.EntityPrefix}$Account22")

    val readJournal = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

    // "Create" the bank accounts previously instantiated.
    val CustomerId = "customer1"
    bankAccountRegion ! CreateBankAccount(CustomerId, Account11)
    bankAccountRegion ! CreateBankAccount(CustomerId, Account22)

    val TransactionId = "transactionId5001"
    val transaction = system.actorOf(PersistentTransactionalActor.props(5.minutes, 5.minutes),
      s"${PersistentTransactionalActor.EntityPrefix}$TransactionId")

    "start a streaming transaction and issue the first entity command" in {
      var events: ListBuffer[PersistentTransactionEvent] = new ListBuffer()
      readJournal.eventsByPersistenceId(s"${PersistentTransactionalActor.EntityPrefix}$TransactionId",
        0L, Long.MaxValue).map(_.event).runForeach {
        case x: PersistentTransactionEvent => events = (events += x).sortWith(_.entityId < _.entityId)
      }(ActorMaterializer()(system))

      transaction ! StartTransaction(TransactionId, "bank-accounts-transaction")
      transaction ! AddStreamingCommand(TransactionId, DepositFunds(Account11, 10), 0L)

      val ExpectedEvents: Seq[PersistentTransactionEvent] = Seq(
        EntityTransactionStarted(TransactionId, Account11, FundsDeposited(Account11, 10)),
        TransactionStarted(TransactionId, "bank-accounts-transaction"),
        StreamingCommandAdded(TransactionId, DepositFunds(Account11, 10), 0L)
      )

      awaitCond(ExpectedEvents == events, timeout.duration, 100.milliseconds,
        s"Expected events of $ExpectedEvents not received.")
    }

    "add the second entity command to the transaction" in {
      var events: ListBuffer[PersistentTransactionEvent] = new ListBuffer()
      readJournal.eventsByPersistenceId(s"${PersistentTransactionalActor.EntityPrefix}$TransactionId",
        0L, Long.MaxValue).map(_.event).runForeach {
        case x: PersistentTransactionEvent => events = (events += x).sortWith(_.entityId < _.entityId)
      }(ActorMaterializer()(system))

      transaction ! AddStreamingCommand(TransactionId, DepositFunds(Account22, 20), 1L)

      val ExpectedEvents: Seq[PersistentTransactionEvent] = Seq(
        EntityTransactionStarted(TransactionId, Account11, FundsDeposited(Account11, 10)),
        EntityTransactionStarted(TransactionId, Account22, FundsDeposited(Account22, 20)),
        TransactionStarted(TransactionId, "bank-accounts-transaction"),
        StreamingCommandAdded(TransactionId, DepositFunds(Account11, 10), 0L),
        StreamingCommandAdded(TransactionId, DepositFunds(Account22, 20), 1L)
      )

      awaitCond(ExpectedEvents == events, timeout.duration, 100.milliseconds,
        s"Expected events of $ExpectedEvents not received.")
    }

    "indicate an end to the stream using EndStreamingCommands() and commit the transaction" in {
      var events: ListBuffer[PersistentTransactionEvent] = new ListBuffer()
      readJournal.eventsByPersistenceId(s"${PersistentTransactionalActor.EntityPrefix}$TransactionId",
        0L, Long.MaxValue).map(_.event).runForeach {
        case x: PersistentTransactionEvent => events = (events += x).sortWith(_.entityId < _.entityId)
      }(ActorMaterializer()(system))

      transaction ! EndStreamingCommands(TransactionId, 2L)

      val ExpectedEvents: Seq[PersistentTransactionEvent] = Seq(
        EntityTransactionStarted(TransactionId, Account11, FundsDeposited(Account11, 10)),
        TransactionCleared(TransactionId, Account11, FundsDeposited(Account11, 10)),
        EntityTransactionStarted(TransactionId, Account22, FundsDeposited(Account22, 20)),
        TransactionCleared(TransactionId, Account22, FundsDeposited(Account22, 20)),
        TransactionStarted(TransactionId, "bank-accounts-transaction"),
        StreamingCommandAdded(TransactionId, DepositFunds(Account11, 10), 0L),
        StreamingCommandAdded(TransactionId, DepositFunds(Account22, 20), 1L),
        StreamingCommandsEnded(TransactionId, 2L),
        PersistentTransactionComplete(TransactionId)
      )

      awaitCond(ExpectedEvents == events, timeout.duration, 100.milliseconds,
        s"Expected events of $ExpectedEvents not received.")

      val probe = TestProbe()
      probe.watch(transaction)
      probe.expectTerminated(transaction, timeout.duration)
    }

    "rollback transaction when with exception on single bank account" in {
      val TransactionId: String = "transactionId7001"

      var events: ListBuffer[PersistentTransactionEvent] = new ListBuffer()
      readJournal.eventsByPersistenceId(s"${PersistentTransactionalActor.EntityPrefix}$TransactionId",
        0L, Long.MaxValue).map(_.event).runForeach {
        case x: PersistentTransactionEvent => events = (events += x).sortWith(_.entityId < _.entityId)
      }(ActorMaterializer()(system))

      val transaction = system.actorOf(PersistentTransactionalActor.props(5.minutes, 5.minutes),
        s"${PersistentTransactionalActor.EntityPrefix}$TransactionId")
      transaction ! StartTransaction(TransactionId, "bank-accounts-transaction")
      transaction ! AddStreamingCommand(TransactionId, WithdrawFunds(Account11, 50), 0L)
      transaction ! AddStreamingCommand(TransactionId, DepositFunds(Account22, 1), 1L)
      transaction ! EndStreamingCommands(TransactionId, 2L)

      val ExpectedEvents: Seq[Any] = Seq(
        EntityTransactionStarted(TransactionId, Account11, InsufficientFunds(Account11, 10, 50)),
        EntityTransactionStarted(TransactionId, Account22, FundsDeposited(Account22, 1)),
        TransactionReversed(TransactionId, Account22, FundsDeposited(Account22, 1)),
        TransactionStarted(TransactionId, "bank-accounts-transaction"),
        StreamingCommandAdded(TransactionId, WithdrawFunds(Account11, 50), 0L),
        StreamingCommandAdded(TransactionId, DepositFunds(Account22, 1), 1L),
        StreamingCommandsEnded(TransactionId, 2L),
        PersistentTransactionComplete(TransactionId)
      )

      awaitCond(ExpectedEvents == events, timeout.duration, 100.milliseconds,
        s"Expected events of $ExpectedEvents not received.")

      val probe = TestProbe()
      probe.watch(transaction)
      probe.expectTerminated(transaction, timeout.duration)
    }

    "recover with incomplete saga state with unresponsive bank account" in {
      val TransactionId: String = "transactionId6001"

      var events: ListBuffer[PersistentTransactionEvent] = new ListBuffer()
      readJournal.eventsByPersistenceId(s"${PersistentTransactionalActor.EntityPrefix}$TransactionId",
        0L, Long.MaxValue).map(_.event).runForeach {
        case x: PersistentTransactionEvent => events = (events += x).sortWith(_.entityId < _.entityId)
      }(ActorMaterializer()(system))

      val transaction = system.actorOf(PersistentTransactionalActor.props(5.minutes, 5.minutes),
        s"${PersistentTransactionalActor.EntityPrefix}$TransactionId")
      transaction ! StartTransaction(TransactionId, "bank-accounts-transaction")
      transaction ! AddStreamingCommand(TransactionId, DepositFunds(Account11, 10), 0L)
      transaction ! AddStreamingCommand(TransactionId, DepositFunds(Account22, 20), 1L)
      transaction ! AddStreamingCommand(TransactionId, DepositFunds("badaccountnumber", 40), 2L) // Non-existing account

      val ExpectedEvents: Seq[Any] = Seq(
        EntityTransactionStarted(TransactionId, Account11, FundsDeposited(Account11, 10)),
        EntityTransactionStarted(TransactionId, Account22, FundsDeposited(Account22, 20)),
        TransactionStarted(TransactionId, "bank-accounts-transaction"),
        StreamingCommandAdded(TransactionId, DepositFunds(Account11, 10), 0L),
        StreamingCommandAdded(TransactionId, DepositFunds(Account22, 20), 1L),
        StreamingCommandAdded(TransactionId, DepositFunds("badaccountnumber", 40), 2L)
      )

      awaitCond(ExpectedEvents == events, timeout.duration, 100.milliseconds,
        s"Expected events of $ExpectedEvents not received.")

      val probe = TestProbe()
      probe.watch(transaction)
      transaction ! PoisonPill
      probe.expectTerminated(transaction, timeout.duration)

      val transaction2 = system.actorOf(PersistentTransactionalActor.props(5.minutes, 5.minutes),
        s"${PersistentTransactionalActor.EntityPrefix}$TransactionId")

      def awaitState() = Await.result((transaction2 ? GetTransactionState).mapTo[TransactionState], timeout.duration)

      awaitCond(awaitState().currentState != "uninitialized", timeout.duration, 100.milliseconds)
      val state = awaitState()
      state.transactionId should be(TransactionId)
      state.description should be("bank-accounts-transaction")
      state.currentState should be("pending")
      state.commands should be(Seq(DepositFunds(Account11, 10), DepositFunds(Account22, 20), DepositFunds("badaccountnumber", 40)))
      state.pendingConfirmed should be(Seq(Account11, Account22))
      state.commitConfirmed should be(Nil)
      state.rollbackConfirmed should be(Nil)
      state.exceptions should be(Nil)
    }
  }
}
