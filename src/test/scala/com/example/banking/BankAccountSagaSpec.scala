package com.example.banking

import java.util.UUID

import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.lightbend.transactional.PersistentSagaActor.{GetSagaState, SagaState}
import com.lightbend.transactional.{NodeTaggedEventSubscription, PersistentSagaActor, TaggedEventSubscription}
import com.lightbend.transactional.PersistentSagaActorCommands._
import com.lightbend.transactional.PersistentSagaActorEvents._
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration._

object BankAccountSagaSpec {

  val Config =
    """
      |akka.actor.provider = "local"
      |akka.actor.warn-about-java-serializer-usage = "false"
      |akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
      |akka.persistence.journal.leveldb.dir = "target/leveldb"
      |akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      |akka.persistence.snapshot-store.local.dir = "target/snapshots"
      |akka-saga.bank-account.saga.retry-after = 5 minutes
    """.stripMargin
}

class BankAccountSagaSpec extends TestKit(ActorSystem("BankAccountSagaSpec", ConfigFactory.parseString(BankAccountSagaSpec.Config)))
  with WordSpecLike with Matchers with ImplicitSender with BeforeAndAfterAll {

  import BankAccountCommands._
  import BankAccountEvents._

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val timeout = Timeout(5.seconds)

  "a BankAccountSaga" should {
    // Bank account shard region mock.
    val bankAccountRegion = system.actorOf(Props(new Actor() {
      override def receive: Receive = {
        case cmd @ CreateBankAccount(_, accountNumber) =>
          system.actorSelection(s"/user/${BankAccountActor.EntityPrefix}$accountNumber") ! cmd
        case tcw: TransactionalCommandWrapper =>
          system.actorSelection(s"/user/${BankAccountActor.EntityPrefix}${tcw.entityId}") ! tcw
      }
    }), s"${BankAccountActor.RegionName}")

    // Saga shard region mock.
    system.actorOf(Props(new Actor() {
      override def receive: Receive = {
        case envelope: TransactionalEventEnvelope =>
          system.actorSelection(s"/user/${PersistentSagaActor.EntityPrefix}${envelope.transactionId}") ! envelope
      }
    }), s"${PersistentSagaActor.RegionName}")

    // Instantiate the bank accounts (sharding would do this in clustered mode).
    val Account11: String = "accountNumber11"
    val Account22: String = "accountNumber22"
    val Account33: String = "accountNumber33"

    system.actorOf(BankAccountActor.props, s"${BankAccountActor.EntityPrefix}$Account11")
    system.actorOf(BankAccountActor.props, s"${BankAccountActor.EntityPrefix}$Account22")
    system.actorOf(BankAccountActor.props, s"${BankAccountActor.EntityPrefix}$Account33")

    val readJournal = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

    // Create node event listener for saga subscription.
    val nodeEventTag: String = UUID.randomUUID().toString
    system.actorOf(NodeTaggedEventSubscription.props(nodeEventTag),
      s"${TaggedEventSubscription.ActorNamePrefix}$nodeEventTag")

    // "Create" the bank accounts previously instantiated.
    val CustomerId = "customer1"
    bankAccountRegion ! CreateBankAccount(CustomerId, Account11)
    bankAccountRegion ! CreateBankAccount(CustomerId, Account22)
    bankAccountRegion ! CreateBankAccount(CustomerId, Account33)

    "commit transaction when no exceptions" in {
      val TransactionId = "transactionId1000"

      var events: ListBuffer[SagaEvent] = new ListBuffer()
      readJournal.eventsByPersistenceId(s"${PersistentSagaActor.EntityPrefix}$TransactionId",
        0L, Long.MaxValue).map(_.event).runForeach {
        case x: SagaEvent => events = (events += x).sortWith(_.entityId < _.entityId)
      }(ActorMaterializer()(system))

      val saga = system.actorOf(PersistentSagaActor.props(nodeEventTag),
        s"${PersistentSagaActor.EntityPrefix}$TransactionId")

      val commands = Seq(
        DepositFunds(Account11, 10),
        DepositFunds(Account22, 20),
        DepositFunds(Account33, 30),
      )

      saga ! StartSaga(TransactionId, "bank-account-saga", commands)

      val ExpectedEvents: Seq[SagaEvent] = Seq(
        TransactionStarted(TransactionId, Account11, nodeEventTag, FundsDeposited(Account11, 10)),
        TransactionCleared(TransactionId, Account11, nodeEventTag),
        TransactionStarted(TransactionId, Account22, nodeEventTag, FundsDeposited(Account22, 20)),
        TransactionCleared(TransactionId, Account22, nodeEventTag),
        TransactionStarted(TransactionId, Account33, nodeEventTag, FundsDeposited(Account33, 30)),
        TransactionCleared(TransactionId, Account33, nodeEventTag),
        SagaTransactionStarted(TransactionId, "bank-account-saga", nodeEventTag, commands),
        SagaTransactionComplete(TransactionId)
      )

      awaitCond(ExpectedEvents == events, timeout.duration, 100.milliseconds,
        s"Expected events of $ExpectedEvents not received.")

      val probe = TestProbe()
      probe.watch(saga)
      saga ! PoisonPill
      probe.expectTerminated(saga, timeout.duration)
    }

    "rollback transaction when with exception on single bank account" in {
      val TransactionId: String = "transactionId2000"

      var events: ListBuffer[SagaEvent] = new ListBuffer()
      readJournal.eventsByPersistenceId(s"${PersistentSagaActor.EntityPrefix}$TransactionId",
        0L, Long.MaxValue).map(_.event).runForeach {
        case x: SagaEvent => events = (events += x).sortWith(_.entityId < _.entityId)
      }(ActorMaterializer()(system))

      val saga = system.actorOf(PersistentSagaActor.props(nodeEventTag),
        s"${PersistentSagaActor.EntityPrefix}$TransactionId")

      val commands = Seq(
        WithdrawFunds("accountNumber11", 11), // cause overdraft
        DepositFunds("accountNumber22", 1),
        DepositFunds("accountNumber33", 2),
      )

      saga ! StartSaga(TransactionId, "bank-account-saga", commands)
      val ExpectedEvents: Seq[Any] = Seq(
        TransactionStarted(TransactionId, Account11, nodeEventTag, InsufficientFunds(Account11, 10, 11)),
        TransactionStarted(TransactionId, Account22, nodeEventTag, FundsDeposited(Account22, 1)),
        TransactionReversed(TransactionId, Account22, nodeEventTag),
        TransactionStarted(TransactionId, Account33, nodeEventTag, FundsDeposited(Account33, 2)),
        TransactionReversed(TransactionId, Account33, nodeEventTag),
        SagaTransactionStarted(TransactionId, "bank-account-saga", nodeEventTag, commands),
        SagaTransactionComplete(TransactionId)
      )

      awaitCond(ExpectedEvents == events, timeout.duration, 100.milliseconds,
        s"Expected events of $ExpectedEvents not received.")

      val probe = TestProbe()
      probe.watch(saga)
      saga ! PoisonPill
      probe.expectTerminated(saga, timeout.duration)
    }

    "recover with incomplete saga state with unresponsive bank account" in {
      val TransactionId: String = "transactionId3000"

      var events: ListBuffer[SagaEvent] = new ListBuffer()
      readJournal.eventsByPersistenceId(s"${PersistentSagaActor.EntityPrefix}$TransactionId",
        0L, Long.MaxValue).map(_.event).runForeach {
        case x: SagaEvent => events = (events += x).sortWith(_.entityId < _.entityId)
      }(ActorMaterializer()(system))

      val saga = system.actorOf(PersistentSagaActor.props(nodeEventTag),
        s"${PersistentSagaActor.EntityPrefix}$TransactionId")

      val commands = Seq(
        DepositFunds("accountNumber11", 100),
        DepositFunds("accountNumber22", 200),
        DepositFunds("accountNumber33", 300),
        DepositFunds("accountNumber44", 400) // Non-existing account
      )

      saga ! StartSaga(TransactionId, "bank-account-saga", commands)

      val ExpectedEvents: Seq[Any] = Seq(
        TransactionStarted(TransactionId, Account11, nodeEventTag, FundsDeposited(Account11, 100)),
        TransactionStarted(TransactionId, Account22, nodeEventTag, FundsDeposited(Account22, 200)),
        TransactionStarted(TransactionId, Account33, nodeEventTag, FundsDeposited(Account33, 300)),
        SagaTransactionStarted(TransactionId, "bank-account-saga", nodeEventTag, commands),
      )

      awaitCond(ExpectedEvents == events, timeout.duration, 100.milliseconds,
        s"Expected events of $ExpectedEvents not received.")

      val probe = TestProbe()
      probe.watch(saga)
      saga ! PoisonPill
      probe.expectTerminated(saga, timeout.duration)

      val saga2 = system.actorOf(PersistentSagaActor.props(nodeEventTag),
        s"${PersistentSagaActor.EntityPrefix}$TransactionId")

      val state = Await.result((saga2 ? GetSagaState).mapTo[SagaState], timeout.duration)
      state.transactionId should be(TransactionId)
      state.description should be("bank-account-saga")
      state.currentState should be("pending")
      state.originalEventTag should be(nodeEventTag)
      state.streamingSaga should be(false)
      state.streamingSagaEnded should be(false)
      state.streamingSequenceNum should be(0L)
      state.commands should be(commands)
      state.pendingConfirmed should be(Seq(Account11, Account22, Account33))
      state.commitConfirmed should be(Nil)
      state.rollbackConfirmed should be(Nil)
      state.exceptions should be(Nil)
    }
  }
}
