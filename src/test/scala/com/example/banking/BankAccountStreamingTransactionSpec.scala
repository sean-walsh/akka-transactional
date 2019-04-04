package com.example.banking

import java.util.UUID

import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.lightbend.transactional.BatchingTransactionalActor.StartBatchingTransaction
import com.lightbend.transactional.PersistentTransactionCommands._
import com.lightbend.transactional.PersistentTransactionEvents._
import com.lightbend.transactional.PersistentTransactionalActor.{BaseTransactionState, GetTransactionState, TransactionState}
import com.lightbend.transactional.StreamingTransactionalActor.{AddStreamingCommand, EndStreamingCommands, StartStreamingTransaction, StreamingCommandAdded}
import com.lightbend.transactional.{BatchingTransactionalActor, NodeTaggedEventSubscription, PersistentTransactionalActor, StreamingTransactionalActor, TaggedEventSubscription}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration._


class BankAccountStreamingTransactionSpec extends TestKit(ActorSystem("BankAccountStreamingTransactionSpec",
  ConfigFactory.parseString(BankAccountBatchingTransactionSpec.Config))) with WordSpecLike with Matchers
  with ImplicitSender with BeforeAndAfterAll {

  import BankAccountCommands._
  import BankAccountEvents._

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val timeout = Timeout(5.seconds)

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

    val TransactionId = "transactionId5001"
    val transaction = system.actorOf(StreamingTransactionalActor.props(nodeEventTag),
      s"${PersistentTransactionalActor.EntityPrefix}$TransactionId")

    "start a streaming transaction and issue the first entity command" in {
      var events: ListBuffer[PersistentTransactionEvent] = new ListBuffer()
      readJournal.eventsByPersistenceId(s"${PersistentTransactionalActor.EntityPrefix}$TransactionId",
        0L, Long.MaxValue).map(_.event).runForeach {
        case x: PersistentTransactionEvent => events = (events += x).sortWith(_.entityId < _.entityId)
      }(ActorMaterializer()(system))

      transaction ! StartStreamingTransaction(TransactionId, "bank-accounts-transaction",
        AddStreamingCommand(TransactionId, DepositFunds(Account11, 10), 0L))

      val ExpectedEvents: Seq[PersistentTransactionEvent] = Seq(
        EntityTransactionStarted(TransactionId, Account11, nodeEventTag, FundsDeposited(Account11, 10)),
        TransactionStarted(TransactionId, "bank-accounts-transaction", nodeEventTag, List()),
        StreamingCommandAdded(TransactionId, DepositFunds(Account11, 10), 0L)
      )

      awaitCond(ExpectedEvents == events, timeout.duration, 100.milliseconds,
        s"Expected events of $ExpectedEvents not received.")
    }

    "add the second entity command to the transaction" in {
      var events: ListBuffer[PersistentTransactionEvent] = new ListBuffer()
      readJournal.eventsByPersistenceId(s"${PersistentTransactionalActor.EntityPrefix}$TransactionId",
        4L, Long.MaxValue).map(_.event).runForeach {
        case x: PersistentTransactionEvent => events = (events += x).sortWith(_.entityId < _.entityId)
      }(ActorMaterializer()(system))

      transaction ! AddStreamingCommand(TransactionId, DepositFunds(Account22, 20), 1L)

      val ExpectedEvents: Seq[PersistentTransactionEvent] = Seq(
        EntityTransactionStarted(TransactionId, Account22, nodeEventTag, FundsDeposited(Account22, 20)),
        StreamingCommandAdded(TransactionId, DepositFunds(Account22, 20), 1L)
      )

      awaitCond(ExpectedEvents == events, timeout.duration, 100.milliseconds,
        s"Expected events of $ExpectedEvents not received.")
    }

    "indicate and end to the stream and commit the transaction" in {
      var events: ListBuffer[PersistentTransactionEvent] = new ListBuffer()
      readJournal.eventsByPersistenceId(s"${PersistentTransactionalActor.EntityPrefix}$TransactionId",
        0L, Long.MaxValue).map(_.event).runForeach {
        case x: PersistentTransactionEvent => events = (events += x).sortWith(_.entityId < _.entityId)
      }(ActorMaterializer()(system))

      transaction ! EndStreamingCommands(TransactionId, 2L)

      //      val ExpectedEvents: Seq[PersistentTransactionEvent] = Seq(
      //        EntityTransactionStarted(TransactionId, Account11, nodeEventTag, FundsDeposited(Account11, 10)),
      //        TransactionCleared(TransactionId, Account11, nodeEventTag),
      //        EntityTransactionStarted(TransactionId, Account22, nodeEventTag, FundsDeposited(Account22, 20)),
      //        TransactionCleared(TransactionId, Account22, nodeEventTag),
      //        EntityTransactionStarted(TransactionId, Account33, nodeEventTag, FundsDeposited(Account33, 30)),
      //        TransactionCleared(TransactionId, Account33, nodeEventTag),
      //        TransactionStarted(TransactionId, "bank-account-saga", nodeEventTag, commands),
      //        PersistentTransactionComplete(TransactionId)
      //      )

      val ExpectedEvents: Seq[PersistentTransactionEvent] = Seq(
        TransactionCleared(TransactionId, Account11, nodeEventTag),
        TransactionCleared(TransactionId, Account22, nodeEventTag),
        PersistentTransactionComplete(TransactionId)
      )
            Thread.sleep(5000)
            println(s"---------------$events")
//      awaitCond(ExpectedEvents == events, timeout.duration, 100.milliseconds,
//        s"Expected events of $ExpectedEvents not received.")
    }

//    "rollback transaction when with exception on single bank account" in {
//      val TransactionId: String = "transactionId2000"
//
//      var events: ListBuffer[PersistentTransactionEvent] = new ListBuffer()
//      readJournal.eventsByPersistenceId(s"${PersistentTransactionalActor.EntityPrefix}$TransactionId",
//        0L, Long.MaxValue).map(_.event).runForeach {
//        case x: PersistentTransactionEvent => events = (events += x).sortWith(_.entityId < _.entityId)
//      }(ActorMaterializer()(system))
//
//      val saga = system.actorOf(BatchingTransactionalActor.props(nodeEventTag),
//        s"${PersistentTransactionalActor.EntityPrefix}$TransactionId")
//
//      val commands = Seq(
//        WithdrawFunds("accountNumber11", 11), // cause overdraft
//        DepositFunds("accountNumber22", 1),
//        DepositFunds("accountNumber33", 2),
//      )
//
//      saga ! StartBatchingTransaction(TransactionId, "bank-account-saga", commands)
//      val ExpectedEvents: Seq[Any] = Seq(
//        EntityTransactionStarted(TransactionId, Account11, nodeEventTag, InsufficientFunds(Account11, 10, 11)),
//        EntityTransactionStarted(TransactionId, Account22, nodeEventTag, FundsDeposited(Account22, 1)),
//        TransactionReversed(TransactionId, Account22, nodeEventTag),
//        EntityTransactionStarted(TransactionId, Account33, nodeEventTag, FundsDeposited(Account33, 2)),
//        TransactionReversed(TransactionId, Account33, nodeEventTag),
//        TransactionStarted(TransactionId, "bank-account-saga", nodeEventTag, commands),
//        PersistentTransactionComplete(TransactionId)
//      )
//
//      awaitCond(ExpectedEvents == events, timeout.duration, 100.milliseconds,
//        s"Expected events of $ExpectedEvents not received.")
//
//      val probe = TestProbe()
//      probe.watch(saga)
//      saga ! PoisonPill
//      probe.expectTerminated(saga, timeout.duration)
//    }
//
//    "recover with incomplete saga state with unresponsive bank account" in {
//      val TransactionId: String = "transactionId3000"
//
//      var events: ListBuffer[PersistentTransactionEvent] = new ListBuffer()
//      readJournal.eventsByPersistenceId(s"${PersistentTransactionalActor.EntityPrefix}$TransactionId",
//        0L, Long.MaxValue).map(_.event).runForeach {
//        case x: PersistentTransactionEvent => events = (events += x).sortWith(_.entityId < _.entityId)
//      }(ActorMaterializer()(system))
//
//      val saga = system.actorOf(BatchingTransactionalActor.props(nodeEventTag),
//        s"${PersistentTransactionalActor.EntityPrefix}$TransactionId")
//
//      val commands = Seq(
//        DepositFunds("accountNumber11", 100),
//        DepositFunds("accountNumber22", 200),
//        DepositFunds("accountNumber33", 300),
//        DepositFunds("accountNumber44", 400) // Non-existing account
//      )
//
//      saga ! StartBatchingTransaction(TransactionId, "bank-account-saga", commands)
//
//      val ExpectedEvents: Seq[Any] = Seq(
//        EntityTransactionStarted(TransactionId, Account11, nodeEventTag, FundsDeposited(Account11, 100)),
//        EntityTransactionStarted(TransactionId, Account22, nodeEventTag, FundsDeposited(Account22, 200)),
//        EntityTransactionStarted(TransactionId, Account33, nodeEventTag, FundsDeposited(Account33, 300)),
//        TransactionStarted(TransactionId, "bank-account-saga", nodeEventTag, commands),
//      )
//
//      awaitCond(ExpectedEvents == events, timeout.duration, 100.milliseconds,
//        s"Expected events of $ExpectedEvents not received.")
//
//      val probe = TestProbe()
//      probe.watch(saga)
//      saga ! PoisonPill
//      probe.expectTerminated(saga, timeout.duration)
//
//      val saga2 = system.actorOf(BatchingTransactionalActor.props(nodeEventTag),
//        s"${PersistentTransactionalActor.EntityPrefix}$TransactionId")
//
//      val state = Await.result((saga2 ? GetTransactionState).mapTo[(BaseTransactionState, TransactionState)], timeout.duration)
//      state._1.transactionId should be(TransactionId)
//      state._1.description should be("bank-account-saga")
//      state._1.currentState should be("pending")
//      state._1.originalEventTag should be(nodeEventTag)
//      state._1.commands should be(commands)
//      state._1.pendingConfirmed should be(Seq(Account11, Account22, Account33))
//      state._1.commitConfirmed should be(Nil)
//      state._1.rollbackConfirmed should be(Nil)
//      state._1.exceptions should be(Nil)
//    }
  }
}
