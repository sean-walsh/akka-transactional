package com.akkatransactional.core

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import TransactionalEntityCommands._
import akka.Done
import com.akkatransactional.core.TransactionalEntityEvents.EntityPendingEvent

import java.util.UUID
import scala.collection.mutable
import scala.collection.mutable.Seq

class CheckpointingTransactionalEntityTest extends ScalaTestWithActorTestKit(s"""
      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
      akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      akka.persistence.snapshot-store.local.dir = "target/snapshot-${UUID.randomUUID().toString}"
    """)
  with AnyWordSpecLike
  with BeforeAndAfterAll
  with Matchers
  with ScalaFutures {

  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
  }

  trait EnrichedEntityLocator {
    def getSentCommands: Seq[TransactionalCommandEnvelope]
  }

  final val TestEntityType = "test-entity-type"
  final val TestEntityId = "entity-id-1"

  case class TestEntityCommand(entityId: String) extends EntityTransactionalCommand

  case class TestEntityPendingEvent(transactionId: String, entityType: String, entityId: String) extends EntityPendingEvent

  "CheckpointingTransactionalEntity" should {
    "start a new transaction" in {
      val entityLocator = mockEntityLocator()
      val txId = "tx1"
      val tx = testKit.spawn(CheckPointingTransactionalEntity(txId, entityLocator))
      val doneProbe = TestProbe[Done]
      tx ! StartTransaction(txId, "desc", doneProbe.ref)
      doneProbe.expectMessage(Done)
    }

    "add a command to the transaction" in {
      val entityLocator = mockEntityLocator()
      val txId = "tx2"
      val tx = testKit.spawn(CheckPointingTransactionalEntity(txId, entityLocator))
      val doneProbe = TestProbe[Done]
      tx ! StartTransaction(txId, "desc", doneProbe.ref)
      doneProbe.expectMessage(Done)
      tx ! AddCommand(txId, TestEntityType, TestEntityId, TestEntityCommand(TestEntityId), 1, doneProbe.ref)
      doneProbe.expectMessage(Done)
    }

    "end commands and remain in pending state" in {
      val entityLocator = mockEntityLocator()
      val txId = "tx3"
      val tx = testKit.spawn(CheckPointingTransactionalEntity(txId, entityLocator))
      val doneProbe = TestProbe[Done]
      tx ! StartTransaction(txId, "desc", doneProbe.ref)
      doneProbe.expectMessage(Done)
      tx ! AddCommand(txId, TestEntityType, TestEntityId, TestEntityCommand(TestEntityId), 1, doneProbe.ref)
      doneProbe.expectMessage(Done)
      tx ! EndCommands(txId, 2, doneProbe.ref)
      doneProbe.expectMessage(Done)
      val stateProbe = TestProbe[ReportedTransactionState]
      tx ! GetCurrentState(txId, stateProbe.ref)
      stateProbe.expectMessage(ReportedTransactionState(txId, "PendingState"))
    }

    "end commands and transition to commit state" in {
      val entityLocator = mockEntityLocator()
      val txId = "tx4"
      val tx = testKit.spawn(CheckPointingTransactionalEntity(txId, entityLocator))
      val doneProbe = TestProbe[Done]
      tx ! StartTransaction(txId, "desc", doneProbe.ref)
      doneProbe.expectMessage(Done)
      tx ! AddCommand(txId, TestEntityType, TestEntityId, TestEntityCommand(TestEntityId), 1, doneProbe.ref)
      doneProbe.expectMessage(Done)
      tx ! EndCommands(txId, 2, doneProbe.ref)
      doneProbe.expectMessage(Done)
      val stateProbe = TestProbe[ReportedTransactionState]
      tx ! ConfirmEntityEvent(txId, TestEntityPendingEvent(txId, TestEntityType, TestEntityId))
      Thread.sleep(5000)
      tx ! GetCurrentState(txId, stateProbe.ref)
      stateProbe.expectMessage(ReportedTransactionState(txId, "CommitState"))
    }
  }

  private def mockEntityLocator(): EntityLocator =
    new EntityLocator with EnrichedEntityLocator {
      val sentCommands: Seq[TransactionalCommandEnvelope] = Seq.empty

      override def sendCommand(command: TransactionalCommandEnvelope): Unit = sentCommands :+ command

      override def getSentCommands: mutable.Seq[TransactionalCommandEnvelope] = sentCommands
    }
}
