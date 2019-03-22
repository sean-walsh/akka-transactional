//package com.example.banking
//
//import akka.NotUsed
//import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props, Terminated}
//import akka.pattern.ask
//import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
//import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
//import akka.persistence.query.{EventEnvelope, Offset, PersistenceQuery}
//import akka.stream.ActorMaterializer
//import akka.stream.scaladsl.Source
//import akka.testkit.{ImplicitSender, TestKit, TestProbe}
//import akka.util.Timeout
//import com.lightbend.transactional.{NodeTaggedEventSubscription, PersistentSagaActor}
//import com.lightbend.transactional.PersistentSagaActorCommands._
//import com.lightbend.transactional.PersistentSagaActorEvents._
//import com.lightbend.transactional.lightbend.{EventTag, TransactionId}
//import com.typesafe.config.ConfigFactory
//import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
//
//import scala.concurrent.Await
//import scala.concurrent.duration._
//
//object BankAccountSagaSpec {
//
//  val Cassandra = false
//
//  private val Journal =
//    if (Cassandra) {
//      """
//        |akka.persistence.journal.plugin = "cassandra-journal"
//        |akka.persistence.snapshot-store.plugin = "cassandra-snapshot-store"
//        |cassandra-query-journal.refresh-interval = 20ms
//      """.stripMargin
//    }
//    else {
//      """
//        |akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
//        |akka.persistence.journal.leveldb.dir = "target/leveldb"
//        |akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
//        |akka.persistence.snapshot-store.local.dir = "target/snapshots"
//      """.stripMargin
//    }
//
//  val Config =
//    """
//      |akka.actor.provider = "local"
//      |akka.actor.warn-about-java-serializer-usage = "false"
//      |akka-saga.bank-account.saga.retry-after = 5 minutes
//      |akka-saga.bank-account.saga.keep-alive-after-completion = 5 minutes
//      |event-subscription-lookup-timeout = 5.seconds
//      |transient-event-subscription-timeout = 5.minutes
//    """.stripMargin + Journal
//}
//
//class BankAccountSagaSpec extends TestKit(ActorSystem("BankAccountSagaSpec", ConfigFactory.parseString(BankAccountSagaSpec.Config)))
//  with WordSpecLike with Matchers with ImplicitSender with BeforeAndAfterAll {
//
//  import BankAccountCommands._
//  import BankAccountEvents._
//  import PersistentSagaActor._
//  import SagaStates._
//
//  override def afterAll: Unit = {
//    TestKit.shutdownActorSystem(system)
//  }
//
//  implicit val timeout =
//    if (BankAccountSagaSpec.Cassandra)
//      Timeout(20.seconds)
//    else
//      Timeout(5.seconds)
//
//  "a BankAccountSaga" should {
//
//    val eventTag: EventTag = "testEventTag"
//    system.actorOf(NodeTaggedEventSubscription.props(eventTag))
//
//    // Instantiate the bank accounts (sharding would do this in clustered mode).
//    system.actorOf(BankAccountActor.props(eventTag), BankAccountActor.EntityPrefix + "accountNumber11")
//    system.actorOf(BankAccountActor.props(eventTag), BankAccountActor.EntityPrefix + "accountNumber22")
//    system.actorOf(BankAccountActor.props(eventTag), BankAccountActor.EntityPrefix + "accountNumber33")
//
//    // Cluster shard mock.
//    val bankAccountRegion = system.actorOf(Props(new Actor() {
//      override def receive: Receive = {
//        case cmd @ CreateBankAccount(_, accountNumber) =>
//          system.actorSelection(s"/user/${BankAccountActor.EntityPrefix}$accountNumber") ! cmd
//        case cmd @ StartTransaction(_, DepositFunds(accountNumber, _)) =>
//          system.actorSelection(s"/user/${BankAccountActor.EntityPrefix}$accountNumber") ! cmd
//        case cmd @ StartTransaction(_, WithdrawFunds(accountNumber, _)) =>
//          system.actorSelection(s"/user/${BankAccountActor.EntityPrefix}$accountNumber") ! cmd
//        case cmd @ CommitTransaction(_, accountNumber) =>
//          system.actorSelection(s"/user/${BankAccountActor.EntityPrefix}$accountNumber") ! cmd
//        case cmd @ RollbackTransaction(_, accountNumber) =>
//          system.actorSelection(s"/user/${BankAccountActor.EntityPrefix}$accountNumber") ! cmd
//        case cmd @ CompleteTransaction(_, accountNumber) =>
//          system.actorSelection(s"/user/${BankAccountActor.EntityPrefix}$accountNumber") ! cmd
//      }
//    }))
//
//    // "Create" the bank accounts previously instantiated.
//    bankAccountRegion ! CreateBankAccount("customer1", "accountNumber11")
//    bankAccountRegion ! CreateBankAccount("customer1", "accountNumber22")
//    bankAccountRegion ! CreateBankAccount("customer1", "accountNumber33")
//
//    val readJournal =
//      if (BankAccountSagaSpec.Cassandra)
//        PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
//      else
//        PersistenceQuery(system).readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)
//
//    // Set up subscription actor to received events from journal, to be easily queried for this spec.
//    case class BankAccountTransactionConfirmed(envelope: TransactionalEventEnvelope, sort: String)
//    case object GetEvents
//    case class GetEventsResult(confirms: Seq[TransactionalEventEnvelope])
//    case object Reset
//    var currentTransaction = ""
//    val eventReceiver: ActorRef = system.actorOf(Props(new Actor {
//      private var confirms: Seq[BankAccountTransactionConfirmed] = Seq.empty
//      override def receive: Receive = {
//        case confirm: BankAccountTransactionConfirmed =>
//          if (confirm.envelope.transactionId == currentTransaction)
//            confirms = confirms :+ confirm
//        case GetEvents =>
//          sender() ! GetEventsResult(
//            confirms.sortWith((a, b) =>
//              a.envelope.event.getClass.getSimpleName + a.sort < b.envelope.event.getClass.getSimpleName + b.sort).map(_.envelope)
//          )
//        case Reset =>
//          confirms = Seq.empty
//      }
//    }))
//    // --
//
//    "commit transaction when no exceptions" in {
//
//      val TransactionId: TransactionId = "transactionId11"
//      currentTransaction = TransactionId
//
//      implicit val mat = ActorMaterializer()(system)
//      val source = readJournal.eventsByTag(eventTag, Offset.noOffset)
//      source.map(_.event).runForeach {
//        case envelope: TransactionalEventEnvelope => eventReceiver ! BankAccountTransactionConfirmed(envelope, envelope.entityId)
//      }
//
//      val saga = system.actorOf(PersistentSagaActor.props(bankAccountRegion, eventTag), TransactionId)
//
//      val cmds = Seq(
//        DepositFunds("accountNumber11", 10),
//        DepositFunds("accountNumber22", 20),
//        DepositFunds("accountNumber33", 30),
//      )
//
//      saga ! StartSaga(TransactionId, "bank-account-saga", cmds)
//      val sagaProbe = TestProbe()
//
//      sagaProbe.awaitCond(Await.result((saga ? GetSagaState)
//        .mapTo[SagaState], timeout.duration).currentState == Complete,
//        timeout.duration, 100.milliseconds, s"Expected state of $Complete not reached.")
//
//      val probe = TestProbe()
//      val ExpectedEvents = Seq(
//        TransactionStarted(TransactionId, "accountNumber11", FundsDeposited("accountNumber11", 10)),
//        TransactionCleared(TransactionId, "accountNumber11", FundsDeposited("accountNumber11", 10)),
//        TransactionStarted(TransactionId, "accountNumber22", FundsDeposited("accountNumber22", 20)),
//        TransactionCleared(TransactionId, "accountNumber22", FundsDeposited("accountNumber22", 20)),
//        TransactionStarted(TransactionId, "accountNumber33", FundsDeposited("accountNumber33", 30)),
//        TransactionCleared(TransactionId, "accountNumber33", FundsDeposited("accountNumber33", 30))
//      )
//
//      probe.awaitCond(Await.result((eventReceiver ? GetEvents)
//        .mapTo[GetEventsResult], timeout.duration).confirms == ExpectedEvents,
//        timeout.duration, 100.milliseconds, s"Expected events of $ExpectedEvents not received.")
//
//      sagaProbe.send(saga, GetSagaState)
//      val resp = sagaProbe.receiveOne(timeout.duration)
//      resp match {
//        case state: SagaState =>
//          state.transactionId should be(TransactionId)
//          state.currentState should be(Complete)
//          state.commands should be(cmds)
//          state.pendingConfirmed.sorted should be(Seq("accountNumber11", "accountNumber22", "accountNumber33"))
//          state.commitConfirmed.sorted should be(Seq("accountNumber11", "accountNumber22", "accountNumber33"))
//          state.rollbackConfirmed should be(Nil)
//          state.exceptions should be(Nil)
//      }
//
//      mat.shutdown()
//    }
//
//    "rollback transaction when with exception and ignore redundant event confirmation from previous spec" in {
//      val TransactionId: TransactionId = "transactionId22"
//      currentTransaction = TransactionId
//      eventReceiver ! Reset
//
//      implicit val mat = ActorMaterializer()(system)
//      val source: Source[EventEnvelope, NotUsed] = readJournal.eventsByTag(eventTag, Offset.noOffset)
//      source.map(_.event).runForeach {
//        case envelope: TransactionalEventEnvelope => eventReceiver ! BankAccountTransactionConfirmed(envelope, envelope.entityId)
//      }
//
//      val saga = system.actorOf(PersistentSagaActor.props(bankAccountRegion, eventTag), TransactionId)
//
//      val cmds = Seq(
//        WithdrawFunds("accountNumber11", 11), // Account having balance of only 10
//        WithdrawFunds("accountNumber22", 20),
//        WithdrawFunds("accountNumber33", 30),
//      )
//
//      saga ! StartSaga(TransactionId, "bank-account-saga", cmds)
//      val sagaProbe: TestProbe = TestProbe()
//
//      sagaProbe.awaitCond(Await.result((saga ? GetSagaState)
//        .mapTo[SagaState], timeout.duration).currentState == Complete,
//        timeout.duration, 100.milliseconds, s"Expected state of $Complete not reached.")
//
//      val probe: TestProbe = TestProbe()
//      val Expected: GetEventsResult = GetEventsResult(
//        Seq(
//          TransactionStarted(TransactionId, "accountNumber22", FundsWithdrawn("accountNumber22", 20)),
//          TransactionReversed(TransactionId, "accountNumber22", FundsWithdrawn("accountNumber22", 20)),
//          TransactionStarted(TransactionId, "accountNumber33", FundsWithdrawn("accountNumber33", 30)),
//          TransactionReversed(TransactionId, "accountNumber33", FundsWithdrawn("accountNumber33", 30)),
//          TransactionStarted(TransactionId, "accountNumber11", InsufficientFunds("accountNumber11", 10, 11)),
//          TransactionComplete(TransactionId, "accountNumber11", InsufficientFunds("accountNumber11", 10, 11)),
//        )
//      )
//
//      probe.awaitCond(Await.result((eventReceiver ? GetEvents)
//        .mapTo[GetEventsResult], timeout.duration) == Expected,
//        timeout.duration, 100.milliseconds, s"Expected events of $Expected not received.")
//
//      sagaProbe.send(saga, GetSagaState)
//      val resp = sagaProbe.receiveOne(timeout.duration)
//      resp match {
//        case state: SagaState =>
//          state.transactionId should be(TransactionId)
//          state.currentState should be(Complete)
//          state.commands should be(cmds)
//          state.pendingConfirmed.sorted should be(Seq("accountNumber22", "accountNumber33"))
//          state.commitConfirmed.sorted should be(Nil)
//          state.rollbackConfirmed.sortWith(_ < _) should be(Seq("accountNumber22", "accountNumber33"))
//          state.exceptions should be(Seq(TransactionStarted(TransactionId, "accountNumber11", InsufficientFunds("accountNumber11", 10, 11))))
//      }
//
//      mat.shutdown()
//    }
//
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
//  }
//}
