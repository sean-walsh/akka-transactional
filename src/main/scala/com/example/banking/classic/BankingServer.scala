//package com.example.banking
//
//import akka.actor.ActorSystem
//import akka.http.scaladsl.{Http2, HttpsConnectionContext}
//import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
//import akka.stream.ActorMaterializer
//
//import scala.concurrent.{ExecutionContext, Future}
//
//class BankingServer(implicit val system: ActorSystem) {
//
//  implicit val mat = ActorMaterializer()
//  implicit val ec: ExecutionContext = system.dispatcher
//
//  val service: HttpRequest => Future[HttpResponse] =
//    com.example.banking.grpc.BankingServiceHandler(new BankingServiceImpl(system))
//
//  Http2().bindAndHandleAsync(
//    service,
//    "127.0.0.1",
//    8080,
//    new HttpsConnectionContext(javax.net.ssl.SSLContext.getDefault())
//  ).foreach { binding =>
//    println(s"GRPC server bound to: ${binding.localAddress}")
//  }
//}
