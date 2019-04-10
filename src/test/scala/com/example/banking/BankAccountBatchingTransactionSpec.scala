package com.example.banking

import java.util.UUID

import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.lightbend.transactional.BatchingTransactionalActor.StartBatchingTransaction
import com.lightbend.transactional.PersistentTransactionalActor.{BaseTransactionState, GetTransactionState, TransactionState}
import com.lightbend.transactional.{BatchingTransactionalActor, NodeTaggedEventSubscription, PersistentTransactionalActor, TaggedEventSubscription}
import com.lightbend.transactional.PersistentTransactionCommands._
import com.lightbend.transactional.PersistentTransactionEvents._
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration._

object BankAccountBatchingTransactionSpec {

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
      |akka-transactional.transient-event-subscription-timeout = 5 minutes
    """.stripMargin
}

class BankAccountBatchingTransactionSpec extends TestKit(ActorSystem("BankAccountBatchingTransactionSpec",
  ConfigFactory.parseString(BankAccountBatchingTransactionSpec.Config))) with WordSpecLike with Matchers
  with ImplicitSender with BeforeAndAfterAll {

  import BankAccountCommands._
  import BankAccountEvents._

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val timeout = Timeout(5.seconds)

  "a bank account BatchingTransactionalActor" should {
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
    val Account110: String = "accountNumber110"
    val Account220: String = "accountNumber220"
    val Account330: String = "accountNumber330"

    system.actorOf(BankAccountActor.props, s"${BankAccountActor.EntityPrefix}$Account110")
    system.actorOf(BankAccountActor.props, s"${BankAccountActor.EntityPrefix}$Account220")
    system.actorOf(BankAccountActor.props, s"${BankAccountActor.EntityPrefix}$Account330")

    val readJournal = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

    // Create node event listener for transaction subscription.
    val nodeEventTag: String = UUID.randomUUID().toString
    system.actorOf(NodeTaggedEventSubscription.props(nodeEventTag),
      s"${TaggedEventSubscription.ActorNamePrefix}$nodeEventTag")

    // "Create" the bank accounts previously instantiated.
    val CustomerId = "customer1"
    bankAccountRegion ! CreateBankAccount(CustomerId, Account110)
    bankAccountRegion ! CreateBankAccount(CustomerId, Account220)
    bankAccountRegion ! CreateBankAccount(CustomerId, Account330)

    "commit transaction when no exceptions" in {
      val TransactionId = "transactionId1000"

      var events: ListBuffer[PersistentTransactionEvent] = new ListBuffer()
      readJournal.eventsByPersistenceId(s"${PersistentTransactionalActor.EntityPrefix}$TransactionId",
        0L, Long.MaxValue).map(_.event).runForeach {
        case x: PersistentTransactionEvent => events = (events += x).sortWith(_.entityId < _.entityId)
      }(ActorMaterializer()(system))

      val transaction = system.actorOf(BatchingTransactionalActor.props(nodeEventTag),
        s"${PersistentTransactionalActor.EntityPrefix}$TransactionId")

      val commands = Seq(
        DepositFunds(Account110, 10),
        DepositFunds(Account220, 20),
        DepositFunds(Account330, 30),
      )

      transaction ! StartBatchingTransaction(TransactionId, "bank-account-transaction", commands)

      val ExpectedEvents: Seq[PersistentTransactionEvent] = Seq(
        EntityTransactionStarted(TransactionId, Account110, nodeEventTag, FundsDeposited(Account110, 10)),
        TransactionCleared(TransactionId, Account110, nodeEventTag),
        EntityTransactionStarted(TransactionId, Account220, nodeEventTag, FundsDeposited(Account220, 20)),
        TransactionCleared(TransactionId, Account220, nodeEventTag),
        EntityTransactionStarted(TransactionId, Account330, nodeEventTag, FundsDeposited(Account330, 30)),
        TransactionCleared(TransactionId, Account330, nodeEventTag),
        TransactionStarted(TransactionId, "bank-account-transaction", nodeEventTag, commands),
        PersistentTransactionComplete(TransactionId)
      )

      awaitCond(ExpectedEvents == events, timeout.duration, 100.milliseconds,
        s"Expected events of $ExpectedEvents not received.")

      val probe = TestProbe()
      probe.watch(transaction)
      transaction ! PoisonPill
      probe.expectTerminated(transaction, timeout.duration)
    }

    "rollback transaction when with exception on single bank account" in {
      val TransactionId: String = "transactionId2000"

      var events: ListBuffer[PersistentTransactionEvent] = new ListBuffer()
      readJournal.eventsByPersistenceId(s"${PersistentTransactionalActor.EntityPrefix}$TransactionId",
        0L, Long.MaxValue).map(_.event).runForeach {
        case x: PersistentTransactionEvent => events = (events += x).sortWith(_.entityId < _.entityId)
      }(ActorMaterializer()(system))

      val transaction = system.actorOf(BatchingTransactionalActor.props(nodeEventTag),
        s"${PersistentTransactionalActor.EntityPrefix}$TransactionId")

      val commands = Seq(
        WithdrawFunds(Account110, 11), // cause overdraft
        DepositFunds(Account220, 1),
        DepositFunds(Account330, 2),
      )

      transaction ! StartBatchingTransaction(TransactionId, "bank-account-transaction", commands)
      val ExpectedEvents: Seq[Any] = Seq(
        EntityTransactionStarted(TransactionId, Account110, nodeEventTag, InsufficientFunds(Account110, 10, 11)),
        EntityTransactionStarted(TransactionId, Account220, nodeEventTag, FundsDeposited(Account220, 1)),
        TransactionReversed(TransactionId, Account220, nodeEventTag),
        EntityTransactionStarted(TransactionId, Account330, nodeEventTag, FundsDeposited(Account330, 2)),
        TransactionReversed(TransactionId, Account330, nodeEventTag),
        TransactionStarted(TransactionId, "bank-account-transaction", nodeEventTag, commands),
        PersistentTransactionComplete(TransactionId)
      )

      awaitCond(ExpectedEvents == events, timeout.duration, 100.milliseconds,
        s"Expected events of $ExpectedEvents not received.")

      val probe = TestProbe()
      probe.watch(transaction)
      transaction ! PoisonPill
      probe.expectTerminated(transaction, timeout.duration)
    }

    "recover with incomplete transaction state with unresponsive bank account" in {
      val TransactionId: String = "transactionId3000"

      var events: ListBuffer[PersistentTransactionEvent] = new ListBuffer()
      readJournal.eventsByPersistenceId(s"${PersistentTransactionalActor.EntityPrefix}$TransactionId",
        0L, Long.MaxValue).map(_.event).runForeach {
        case x: PersistentTransactionEvent => events = (events += x).sortWith(_.entityId < _.entityId)
      }(ActorMaterializer()(system))

      val transaction = system.actorOf(BatchingTransactionalActor.props(nodeEventTag),
        s"${PersistentTransactionalActor.EntityPrefix}$TransactionId")

      val commands = Seq(
        DepositFunds(Account110, 100),
        DepositFunds(Account220, 200),
        DepositFunds(Account330, 300),
        DepositFunds("accountNumber440", 400) // Non-existing account
      )

      transaction ! StartBatchingTransaction(TransactionId, "bank-account-transaction", commands)

      val ExpectedEvents: Seq[Any] = Seq(
        EntityTransactionStarted(TransactionId, Account110, nodeEventTag, FundsDeposited(Account110, 100)),
        EntityTransactionStarted(TransactionId, Account220, nodeEventTag, FundsDeposited(Account220, 200)),
        EntityTransactionStarted(TransactionId, Account330, nodeEventTag, FundsDeposited(Account330, 300)),
        TransactionStarted(TransactionId, "bank-account-transaction", nodeEventTag, commands),
      )

      awaitCond(ExpectedEvents == events, timeout.duration, 100.milliseconds,
        s"Expected events of $ExpectedEvents not received.")

      val probe = TestProbe()
      probe.watch(transaction)
      transaction ! PoisonPill
      probe.expectTerminated(transaction, timeout.duration)

      val transaction2 = system.actorOf(BatchingTransactionalActor.props(nodeEventTag),
        s"${PersistentTransactionalActor.EntityPrefix}$TransactionId")

      def awaitState() = Await.result((transaction2 ? GetTransactionState).mapTo[(BaseTransactionState, TransactionState)], timeout.duration)

      awaitCond(awaitState()._1.currentState != "uninitialized", timeout.duration, 100.milliseconds)
      val state = awaitState()
      state._1.transactionId should be(TransactionId)
      state._1.description should be("bank-account-transaction")
      state._1.currentState should be("pending")
      state._1.originalEventTag should be(nodeEventTag)
      state._1.commands should be(commands)
      state._1.pendingConfirmed should be(Seq(Account110, Account220, Account330))
      state._1.commitConfirmed should be(Nil)
      state._1.rollbackConfirmed should be(Nil)
      state._1.exceptions should be(Nil)
    }
  }
}
