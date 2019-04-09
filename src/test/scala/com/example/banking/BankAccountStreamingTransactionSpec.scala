package com.example.banking

import java.util.UUID

import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.lightbend.transactional.PersistentTransactionCommands._
import com.lightbend.transactional.PersistentTransactionEvents._
import com.lightbend.transactional.PersistentTransactionalActor.{BaseTransactionState, GetTransactionState, TransactionState}
import com.lightbend.transactional.StreamingTransactionalActor.{AddStreamingCommand, EndStreamingCommands, StartStreamingTransaction, StreamingCommandAdded, StreamingCommandsEnded}
import com.lightbend.transactional.{NodeTaggedEventSubscription, PersistentTransactionalActor, StreamingTransactionalActor, TaggedEventSubscription}
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

    system.actorOf(BankAccountActor.props, s"${BankAccountActor.EntityPrefix}$Account11")
    system.actorOf(BankAccountActor.props, s"${BankAccountActor.EntityPrefix}$Account22")

    val readJournal = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)

    // Create node event listener for saga subscription.
    val nodeEventTag: String = UUID.randomUUID().toString
    system.actorOf(NodeTaggedEventSubscription.props(nodeEventTag),
      s"${TaggedEventSubscription.ActorNamePrefix}$nodeEventTag")

    // "Create" the bank accounts previously instantiated.
    val CustomerId = "customer1"
    bankAccountRegion ! CreateBankAccount(CustomerId, Account11)
    bankAccountRegion ! CreateBankAccount(CustomerId, Account22)

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
        TransactionStarted(TransactionId, "bank-accounts-transaction", nodeEventTag, Nil),
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
        EntityTransactionStarted(TransactionId, Account11, nodeEventTag, FundsDeposited(Account11, 10)),
        EntityTransactionStarted(TransactionId, Account22, nodeEventTag, FundsDeposited(Account22, 20)),
        TransactionStarted(TransactionId, "bank-accounts-transaction", nodeEventTag, Nil),
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
        EntityTransactionStarted(TransactionId, Account11, nodeEventTag, FundsDeposited(Account11, 10)),
        TransactionCleared(TransactionId, Account11, nodeEventTag),
        EntityTransactionStarted(TransactionId, Account22, nodeEventTag, FundsDeposited(Account22, 20)),
        TransactionCleared(TransactionId, Account22, nodeEventTag),
        TransactionStarted(TransactionId, "bank-accounts-transaction", nodeEventTag, Nil),
        StreamingCommandAdded(TransactionId, DepositFunds(Account11, 10), 0L),
        StreamingCommandAdded(TransactionId, DepositFunds(Account22, 20), 1L),
        StreamingCommandsEnded(TransactionId, 2L),
        PersistentTransactionComplete(TransactionId)
      )

      awaitCond(ExpectedEvents == events, timeout.duration, 100.milliseconds,
        s"Expected events of $ExpectedEvents not received.")
    }

    "recover with incomplete saga state with unresponsive bank account" in {
      val TransactionId: String = "transactionId6001"

      var events: ListBuffer[PersistentTransactionEvent] = new ListBuffer()
      readJournal.eventsByPersistenceId(s"${PersistentTransactionalActor.EntityPrefix}$TransactionId",
        0L, Long.MaxValue).map(_.event).runForeach {
        case x: PersistentTransactionEvent => events = (events += x).sortWith(_.entityId < _.entityId)
      }(ActorMaterializer()(system))

      val transaction = system.actorOf(StreamingTransactionalActor.props(nodeEventTag),
        s"${PersistentTransactionalActor.EntityPrefix}$TransactionId")
      transaction ! StartStreamingTransaction(TransactionId, "bank-accounts-transaction",
        AddStreamingCommand(TransactionId, DepositFunds(Account11, 10), 0L))
      transaction ! AddStreamingCommand(TransactionId, DepositFunds(Account22, 20), 1L)
      transaction ! AddStreamingCommand(TransactionId, DepositFunds("badaccountnumber", 40), 2L) // Non-existing account

      val ExpectedEvents: Seq[Any] = Seq(
        EntityTransactionStarted(TransactionId, Account11, nodeEventTag, FundsDeposited(Account11, 10)),
        EntityTransactionStarted(TransactionId, Account22, nodeEventTag, FundsDeposited(Account22, 20)),
        TransactionStarted(TransactionId, "bank-accounts-transaction", nodeEventTag, Nil),
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

      val transaction2 = system.actorOf(StreamingTransactionalActor.props(nodeEventTag),
        s"${PersistentTransactionalActor.EntityPrefix}$TransactionId")

      def awaitState(): = Await.result((transaction2 ? GetTransactionState).mapTo[(BaseTransactionState, TransactionState)], timeout.duration)
      state._1.transactionId should be(TransactionId)
      state._1.description should be("bank-accounts-transaction")
      state._1.currentState should be("pending")
      state._1.originalEventTag should be(nodeEventTag)
      state._1.commands should be(Seq(DepositFunds(Account11, 10), DepositFunds(Account22, 20), DepositFunds("badaccountnumber", 40)))
      state._1.pendingConfirmed should be(Seq(Account11, Account22))
      state._1.commitConfirmed should be(Nil)
      state._1.rollbackConfirmed should be(Nil)
      state._1.exceptions should be(Nil)
    }
  }
}
