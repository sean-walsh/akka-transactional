package com.example.banking

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.Cluster
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.example.banking.BankAccountCommands.BankAccountCommand
import com.lightbend.transactional.PersistentSagaActorEvents.TransactionalEventEnvelope
import com.lightbend.transactional._
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.math.abs

/**
  * Test friendly abstract application.
  */
abstract class BaseApp(implicit val system: ActorSystem) {

  import PersistentSagaActorCommands._

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
  val bankAccountShardCount: Int = system.settings.config.getInt("akka-saga.bank-account.shard-count")
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

  // Set up saga cluster sharding
  val sagaEntityIdExtractor: ShardRegion.ExtractEntityId = {
    case cmd: StartSaga => (s"${PersistentSagaActor.EntityPrefix}${cmd.transactionId}", cmd)
  }
  val sagaShardCount: Int = system.settings.config.getInt("akka-saga.bank-account.saga.shard-count")
  val sagaShardIdExtractor: ShardRegion.ExtractShardId = {
    case cmd: StartSaga =>
      abs(cmd.transactionId.hashCode % sagaShardCount).toString
    case msg: TransactionalEventEnvelope =>
      abs(msg.transactionId.hashCode % sagaShardCount).toString
    case ShardRegion.StartEntity(id) =>
      abs(id.hashCode % sagaShardCount).toString
  }
  val bankAccountSagaRegion: ActorRef = ClusterSharding(system).start(
    typeName = PersistentSagaActor.RegionName,
    entityProps = PersistentSagaActor.props(nodeEventTag),
    settings = ClusterShardingSettings(system),
    extractEntityId = sagaEntityIdExtractor,
    extractShardId = sagaShardIdExtractor
  )

  /**
    * Main function for running the app.
    */
  protected def run(): Unit = {
    createHttpServer()
  }

  /**
    * Create Akka Http Server
    *
    * @return BankAccountHttpServer
    */
  private def createHttpServer(): BankAccountHttpServer = {
    implicit val timeout: Timeout = Timeout(5.seconds)
    new BankAccountHttpServer(bankAccountRegion, bankAccountSagaRegion)(system, timeout)
  }
}
