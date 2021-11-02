//package com.example.banking.classic
//
//import akka.actor.{ActorRef, ActorSystem}
//import akka.cluster.Cluster
//import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
//import akka.stream.ActorMaterializer
//import com.example.banking.classic.BankAccountCommands.BankAccountCommand
//import com.akkatransactional.classic.PersistentTransactionCommands.StartTransaction
//import com.akkatransactional.classic.PersistentTransactionEvents.TransactionalEventEnvelope
//import com.akkatransactional.classic._
//import com.typesafe.config.ConfigFactory
//
//import scala.concurrent.duration._
//import scala.math.abs
//
///**
//  * Test friendly abstract application.
//  */
//abstract class BaseApp(implicit val system: ActorSystem) {
//
//  lazy val config = ConfigFactory.load()
//  implicit val materializer = ActorMaterializer()
//  implicit val executionContext = system.dispatcher
//  implicit val cluster = Cluster(system)
//
//  // Set up bank account cluster sharding
//  val bankAccountEntityIdExtractor: ShardRegion.ExtractEntityId = {
//    case cmd: BankAccountCommand => (s"${BankAccountActor.EntityPrefix}${cmd.accountNumber}", cmd)
//  }
//  val bankAccountShardCount: Int = system.settings.config.getInt("banking.bank-account.shard-count")
//  val bankAccountShardIdExtractor: ShardRegion.ExtractShardId = {
//    case cmd: BankAccountCommand =>
//      abs(cmd.accountNumber.hashCode % bankAccountShardCount).toString
//    case ShardRegion.StartEntity(id) =>
//      abs(id.hashCode % bankAccountShardCount).toString
//  }
//  val bankAccountRegion: ActorRef = ClusterSharding(system).start(
//    typeName = "bank-account",
//    entityProps = BankAccountActor.props,
//    settings = ClusterShardingSettings(system),
//    extractEntityId = bankAccountEntityIdExtractor,
//    extractShardId = bankAccountShardIdExtractor
//  )
//
//  // Set up transaction cluster sharding
//  val transactionEntityIdExtractor: ShardRegion.ExtractEntityId = {
//    case cmd: StartTransaction => (s"${PersistentTransactionalActor.EntityPrefix}${cmd.transactionId}", cmd)
//  }
//  val transactionShardCount: Int = system.settings.config.getInt("banking.bank-account.akka-transactional.shard-count")
//
//  val transactionShardIdExtractor: ShardRegion.ExtractShardId = {
//    case cmd: StartTransaction =>
//      abs(cmd.transactionId.hashCode % transactionShardCount).toString
//    case msg: TransactionalEventEnvelope =>
//      abs(msg.transactionId.hashCode % transactionShardCount).toString
//    case ShardRegion.StartEntity(id) =>
//      abs(id.hashCode % transactionShardCount).toString
//  }
//
//  val retryAfter: FiniteDuration =
//    system.settings.config.getDuration("banking.bank-account.akka-transactional.retry-after").toNanos.nanos
//
//  val timeoutAfter: FiniteDuration =
//    system.settings.config.getDuration("banking.bank-account.akka-transactional.timeout-after").toNanos.nanos
//
//  val bankAccountTransactionRegion: ActorRef = ClusterSharding(system).start(
//    typeName = PersistentTransactionalActor.RegionName,
//    entityProps = PersistentTransactionalActor.props(retryAfter, timeoutAfter),
//    settings = ClusterShardingSettings(system),
//    extractEntityId = transactionEntityIdExtractor,
//    extractShardId = transactionShardIdExtractor
//  )
//
//  /**
//    * Main function for running the app.
//    */
//  protected def run(): Unit =
//    new BankingServer()
//}
