package com.example.bankaccount

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

/**
  * This is the runtime app, which wraps the abstract BaseApp
  */
object AkkaSagaApp {
  def main(args: Array[String]): Unit = {

    implicit val system: ActorSystem = ActorSystem("akka-saga-app", ConfigFactory.load())

    val app: AkkaSagaApp = new AkkaSagaApp()
    app.run()
  }
}

class AkkaSagaApp(implicit system: ActorSystem) extends BaseApp()
