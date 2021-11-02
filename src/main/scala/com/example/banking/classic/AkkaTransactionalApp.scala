//package com.example.banking.classic
//
//import akka.actor.ActorSystem
//import com.typesafe.config.ConfigFactory
//
///**
//  * This is the runtime app, which wraps the abstract BaseApp
//  */
//object AkkaTransactionalApp {
//  def main(args: Array[String]): Unit = {
//
//    implicit val system: ActorSystem = ActorSystem("akka-transactional-app", ConfigFactory.load())
//
//    val app: AkkaTransactionalApp = new AkkaTransactionalApp()
//    app.run()
//  }
//}
//
//class AkkaTransactionalApp(implicit system: ActorSystem) extends BaseApp()
