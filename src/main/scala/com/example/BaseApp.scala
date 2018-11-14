package com.example

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings}
import akka.util.Timeout
import com.example.bankaccount.BankAccount

import scala.concurrent.duration._
import scala.math.abs

/**
  * Test friendly abstract application.
  * @param system ActorSystem
  */
abstract class BaseApp(implicit val system: ActorSystem) {

  import bankaccount.BankAccountCommands._
  import PersistentSagaActorCommands._

  // Set up bank account cluster sharding
  val bankAccountEntityIdExtractor: ShardRegion.ExtractEntityId = {
    case cmd: BankAccountCommand => (BankAccount.EntityPrefix + cmd.accountNumber, cmd)
  }
  val bankAccountShardCount: Int = system.settings.config.getInt("akka-saga.bank-account.shard-count")
  val bankAccountShardIdExtractor: ShardRegion.ExtractShardId = {
    case cmd: BankAccountCommand =>
      abs(cmd.accountNumber.hashCode % bankAccountShardCount).toString
    case ShardRegion.StartEntity(id) ⇒
      // StartEntity is used by remembering entities feature
      abs(id.hashCode % bankAccountShardCount).toString
  }
  val bankAccountRegion: ActorRef = ClusterSharding(system).start(
    typeName = "bank-account",
    entityProps = bankaccount.BankAccount.props(),
    settings = ClusterShardingSettings(system),
    extractEntityId = bankAccountEntityIdExtractor,
    extractShardId = bankAccountShardIdExtractor
  )

  // Per node event subscriber.
  val eventSubscriberUniqueName = system.settings.config.getString("akka-saga.event-subscriber.unique-name")
  system.actorOf(
    ClusterSingletonManager.props(
      singletonProps = EventSubscriptionNodeSingleton.props,
      terminationMessage = EventSubscriptionNodeSingleton.StopEventSubscriptionNodeSingleton,
      settings = ClusterSingletonManagerSettings(system)),
    name = eventSubscriberUniqueName) // This must map to unique roll name in config. (use Lightbend Orchestration magic)

  // Proxy for above.
  val eventSubscriberProxy = system.actorOf(
    ClusterSingletonProxy.props(
      singletonManagerPath = s"/user/$eventSubscriberUniqueName",
      settings = ClusterSingletonProxySettings(system)),
    name = s"${eventSubscriberUniqueName}Proxy")

  // Set up saga cluster sharding
  val sagaEntityIdExtractor: ShardRegion.ExtractEntityId = {
    case cmd: StartSaga => (PersistentSagaActor.EntityPrefix + cmd.transactionId, cmd)
  }
  val sagaShardCount: Int = system.settings.config.getInt("akka-saga.bank-account.saga.shard-count")
  val sagaShardIdExtractor: ShardRegion.ExtractShardId = {
    case cmd: StartSaga =>
      abs(cmd.transactionId.hashCode % sagaShardCount).toString
    case ShardRegion.StartEntity(id) ⇒
      abs(id.hashCode % sagaShardCount).toString
  }
  val bankAccountSagaRegion: ActorRef = ClusterSharding(system).start(
    typeName = "bank-account-saga",
    entityProps = PersistentSagaActor.props(bankAccountRegion, eventSubscriberProxy),
    settings = ClusterShardingSettings(system),
    extractEntityId = sagaEntityIdExtractor,
    extractShardId = bankAccountShardIdExtractor
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
  private def createHttpServer(): AkkaSagaHttpServer = {
    implicit val timeout: Timeout = Timeout(5.seconds)
    new AkkaSagaHttpServer(bankAccountRegion, bankAccountSagaRegion)(system, timeout)
  }
}
