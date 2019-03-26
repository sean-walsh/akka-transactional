package com.example.banking

import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.lightbend.transactional.PersistentSagaActor
import com.lightbend.transactional.PersistentSagaActorCommands._
import com.lightbend.transactional.PersistentSagaActorEvents._
import com.lightbend.transactional.lightbend.TransactionId
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.collection.mutable.ListBuffer
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
        case msg @ SagaDeliveryReceipt(entityId) =>
          system.actorSelection(s"/user/${BankAccountActor.EntityPrefix}${entityId}") ! msg
      }
    }), "bank-account-region")

    // Saga shard region mock.
    system.actorOf(Props(new Actor() {
      override def receive: Receive = {
        case envelope: TransactionalEventEnvelope =>
          system.actorSelection(s"/user/${PersistentSagaActor.EntityPrefix}${envelope.transactionId}") ! envelope
      }
    }), "persistent-saga-region")

    // Instantiate the bank accounts (sharding would do this in clustered mode).
    val Account11: String = "accountNumber11"
    val Account22: String = "accountNumber22"
    val Account33: String = "accountNumber33"

    system.actorOf(BankAccountActor.props, s"${BankAccountActor.EntityPrefix}$Account11")
    system.actorOf(BankAccountActor.props, s"${BankAccountActor.EntityPrefix}$Account22")
    system.actorOf(BankAccountActor.props, s"${BankAccountActor.EntityPrefix}$Account33")

    var events11: ListBuffer[Any] = new ListBuffer[Any]()
    var events22: ListBuffer[Any] = new ListBuffer[Any]()
    var events33: ListBuffer[Any] = new ListBuffer[Any]()

    val readJournal = PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)
    readJournal.eventsByPersistenceId(s"${BankAccountActor.EntityPrefix}$Account11", 2L, Long.MaxValue).map(_.event).runForeach {
      case x => events11 += x
    }(ActorMaterializer()(system))
    readJournal.eventsByPersistenceId(s"${BankAccountActor.EntityPrefix}$Account22", 2L, Long.MaxValue).map(_.event).runForeach {
      case x => events22 += x
    }(ActorMaterializer()(system))
    readJournal.eventsByPersistenceId(s"${BankAccountActor.EntityPrefix}$Account33", 2L, Long.MaxValue).map(_.event).runForeach {
      case x => events33 += x
    }(ActorMaterializer()(system))

    // "Create" the bank accounts previously instantiated.
    val CustomerId = "customer1"
    bankAccountRegion ! CreateBankAccount(CustomerId, Account11)
    bankAccountRegion ! CreateBankAccount(CustomerId, Account22)
    bankAccountRegion ! CreateBankAccount(CustomerId, Account33)

    "commit transaction when no exceptions" in {
      val TransactionId: TransactionId = "transactionId11"
      val saga = system.actorOf(PersistentSagaActor.props(bankAccountRegion), s"${PersistentSagaActor.EntityPrefix}$TransactionId")

      val cmds = Seq(
        DepositFunds(Account11, 10),
        DepositFunds(Account22, 20),
        DepositFunds(Account33, 30),
      )

      saga ! StartSaga(TransactionId, "bank-account-saga", cmds)

      val ExpectedEvents11: Seq[Any] = Seq(
        TransactionStarted(TransactionId, Account11, FundsDeposited(Account11, 10)),
        EventConfirmedReceipt(SagaDeliveryReceipt(Account11), TransactionStarted(TransactionId, Account11, FundsDeposited(Account11, 10))),
        TransactionCleared(TransactionId, Account11),
        EventConfirmedReceipt(SagaDeliveryReceipt(Account11), TransactionCleared(TransactionId, Account11))
      )

      val ExpectedEvents22: Seq[Any] = Seq(
        TransactionStarted(TransactionId, Account22, FundsDeposited(Account22, 20)),
        EventConfirmedReceipt(SagaDeliveryReceipt(Account22), TransactionStarted(TransactionId, Account22, FundsDeposited(Account22, 20))),
        TransactionCleared(TransactionId, Account22),
        EventConfirmedReceipt(SagaDeliveryReceipt(Account22), TransactionCleared(TransactionId, Account22))
      )

      val ExpectedEvents33: Seq[Any] = Seq(
        TransactionStarted(TransactionId, Account33, FundsDeposited(Account33, 30)),
        EventConfirmedReceipt(SagaDeliveryReceipt(Account33), TransactionStarted(TransactionId, Account33, FundsDeposited(Account33, 30))),
        TransactionCleared(TransactionId, Account33),
        EventConfirmedReceipt(SagaDeliveryReceipt(Account33), TransactionCleared(TransactionId, Account33))
      )

      awaitCond(ExpectedEvents11 == events11, timeout.duration, 100.milliseconds, s"Expected events of $ExpectedEvents11 not received for $Account11.")
      awaitCond(ExpectedEvents22 == events22, timeout.duration, 100.milliseconds, s"Expected events of $ExpectedEvents22 not received for $Account22.")
      awaitCond(ExpectedEvents33 == events33, timeout.duration, 100.milliseconds, s"Expected events of $ExpectedEvents33 not received for $Account33.")
      val probe = TestProbe()
      probe.watch(saga)
      saga ! PoisonPill
      probe.expectTerminated(saga, timeout.duration)
    }

//    "rollback transaction when with exception on single bank account" in {
//      events11 = new ListBuffer[Any]()
//      events22 = new ListBuffer[Any]()
//      events33 = new ListBuffer[Any]()
//      val TransactionId: TransactionId = "transactionId22"
//      val saga = system.actorOf(PersistentSagaActor.props(bankAccountRegion), s"${PersistentSagaActor.EntityPrefix}$TransactionId")
//
//      val cmds = Seq(
//        WithdrawFunds("accountNumber11", 11), // cause overdraft
//        DepositFunds("accountNumber22", 1),
//        DepositFunds("accountNumber33", 2),
//      )
//
//      saga ! StartSaga(TransactionId, "bank-account-saga", cmds)
//      val ExpectedEvents11: Seq[Any] = Seq(
//        TransactionStarted(TransactionId, Account11, InsufficientFunds(Account11, 10, 11)),
//        EventConfirmedReceipt(SagaDeliveryReceipt(Account11), TransactionStarted(TransactionId, Account11, InsufficientFunds(Account11, 10, 11)))
//      )
//
//      val ExpectedEvents22: Seq[Any] = Seq(
//        TransactionStarted(TransactionId, Account22, FundsDeposited(Account22, 20)),
//        EventConfirmedReceipt(SagaDeliveryReceipt(Account22), TransactionStarted(TransactionId, Account22, FundsDeposited(Account22, 20))),
//        TransactionReversed(TransactionId, Account22),
//        EventConfirmedReceipt(SagaDeliveryReceipt(Account22), TransactionReversed(TransactionId, Account22))
//      )
//
//      val ExpectedEvents33: Seq[Any] = Seq(
//        TransactionStarted(TransactionId, Account33, FundsDeposited(Account33, 30)),
//        EventConfirmedReceipt(SagaDeliveryReceipt(Account33), TransactionStarted(TransactionId, Account33, FundsDeposited(Account33, 30))),
//        TransactionReversed(TransactionId, Account33),
//        EventConfirmedReceipt(SagaDeliveryReceipt(Account33), TransactionReversed(TransactionId, Account33))
//      )
//
//      Thread.sleep(5000)
//      println(events11.mkString(",\n"))
//      println(events22.mkString(",\n"))
//      println(events33.mkString(",\n"))
//      awaitCond(ExpectedEvents11 == events11, timeout.duration, 100.milliseconds, s"Expected events of $ExpectedEvents11 not received for $Account11.")
//      awaitCond(ExpectedEvents22 == events22, timeout.duration, 100.milliseconds, s"Expected events of $ExpectedEvents22 not received for $Account22.")
//      awaitCond(ExpectedEvents33 == events33, timeout.duration, 100.milliseconds, s"Expected events of $ExpectedEvents33 not received for $Account33.")
//      val probe = TestProbe()
//      probe.watch(saga)
//      saga ! PoisonPill
//      probe.expectTerminated(saga, timeout.duration)
//    }

//    "replay properly" in {
//      val TransactionId: TransactionId = "transactionId33"
//      currentTransaction = TransactionId
//      eventReceiver ! Reset
//
//      val saga = system.actorOf(PersistentSagaActor.props(bankAccountRegion, eventTag), TransactionId)
//
//      val cmds = Seq(
//        DepositFunds("accountNumber11", 50),
//        DepositFunds("accountNumber22", 60),
//        DepositFunds("accountNumber33", 70),
//      )
//
//      saga ! StartSaga(TransactionId, "bank-account-saga", cmds)
//      val sagaProbe: TestProbe = TestProbe()
//      sagaProbe.watch(saga)
//
//      sagaProbe.awaitCond(Await.result((saga ? GetSagaState)
//        .mapTo[SagaState], timeout.duration).currentState == Complete,
//        timeout.duration, 100.milliseconds, s"Expected state of $Complete not reached.")
//
//      saga ! PoisonPill
//      sagaProbe.expectMsgClass(classOf[Terminated])
//
//      val saga2 = system.actorOf(PersistentSagaActor.props(bankAccountRegion, eventTag), TransactionId)
//
//      sagaProbe.awaitCond(Await.result((saga2 ? GetSagaState)
//        .mapTo[SagaState], timeout.duration).currentState == Complete,
//        timeout.duration, 100.milliseconds, s"Expected state of $Complete not reached.")
//    }
  }
}
