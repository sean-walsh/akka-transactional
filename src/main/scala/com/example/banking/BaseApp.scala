package com.example.banking

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.Cluster
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.example.banking.BankAccountCommands.BankAccountCommand
import com.lightbend.transactional.BatchingTransactionalActor.StartBatchingTransaction
import com.lightbend.transactional.PersistentTransactionEvents.TransactionalEventEnvelope
import com.lightbend.transactional._
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.math.abs

/**
  * Test friendly abstract application.
  */
abstract class BaseApp(implicit val system: ActorSystem) {

  lazy val config = ConfigFactory.load()
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher
  implicit val cluster = Cluster(system)

  // Generate a unique eventTag to be used for tagging all event for entities instantiated on this node.
  val nodeEventTag: String = UUID.randomUUID().toString

  // Per node event subscriber.
  system.actorOf(NodeTaggedEventSubscription.props(nodeEventTag),
    s"${TaggedEventSubscription.ActorNamePrefix}$nodeEventTag")

  // Set up bank account cluster sharding
  val bankAccountEntityIdExtractor: ShardRegion.ExtractEntityId = {
    case cmd: BankAccountCommand => (s"${BankAccountActor.EntityPrefix}${cmd.accountNumber}", cmd)
  }
  val bankAccountShardCount: Int = system.settings.config.getInt("banking.bank-account.shard-count")
  val bankAccountShardIdExtractor: ShardRegion.ExtractShardId = {
    case cmd: BankAccountCommand =>
      abs(cmd.accountNumber.hashCode % bankAccountShardCount).toString
    case ShardRegion.StartEntity(id) =>
      abs(id.hashCode % bankAccountShardCount).toString
  }
  val bankAccountRegion: ActorRef = ClusterSharding(system).start(
    typeName = "bank-account",
    entityProps = BankAccountActor.props,
    settings = ClusterShardingSettings(system),
    extractEntityId = bankAccountEntityIdExtractor,
    extractShardId = bankAccountShardIdExtractor
  )

  // Set up transaction cluster sharding
  val transactionEntityIdExtractor: ShardRegion.ExtractEntityId = {
    case cmd: StartBatchingTransaction => (s"${PersistentTransactionalActor.EntityPrefix}${cmd.transactionId}", cmd)
  }
  val transactionShardCount: Int = system.settings.config.getInt("banking.bank-account.akka-transactional.shard-count")
  val transactionShardIdExtractor: ShardRegion.ExtractShardId = {
    case cmd: StartBatchingTransaction =>
      abs(cmd.transactionId.hashCode % transactionShardCount).toString
    case msg: TransactionalEventEnvelope =>
      abs(msg.transactionId.hashCode % transactionShardCount).toString
    case ShardRegion.StartEntity(id) =>
      abs(id.hashCode % transactionShardCount).toString
  }
  val bankAccountTransactionRegion: ActorRef = ClusterSharding(system).start(
    typeName = PersistentTransactionalActor.RegionName,
    entityProps = BatchingTransactionalActor.props(nodeEventTag),
    settings = ClusterShardingSettings(system),
    extractEntityId = transactionEntityIdExtractor,
    extractShardId = transactionShardIdExtractor
  )

  /**
    * Main function for running the app.
    */
  protected def run(): Unit = {
    createHttpServer()
  }

  /**
    * Create Akka Http Server
    */
  private def createHttpServer(): BankAccountHttpServer = {
    implicit val timeout: Timeout = Timeout(5.seconds)
    new BankAccountHttpServer(bankAccountRegion, bankAccountTransactionRegion)(system, timeout)
  }
}
