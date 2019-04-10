package com.example.banking

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{as, complete, entity, path, post}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.util.Timeout
import spray.json._
import BankAccountCommands._
import com.lightbend.transactional.BatchingTransactionalActor.StartBatchingTransaction

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * A wrapper to start a transaction containing bank account transactional commands.
  */
case class StartBankAccountTransaction(deposits: Seq[DepositFundsDto], withdrawals: Seq[WithdrawFundsDto])

/**
  * A DTO for WithdrawFunds.
  */
case class DepositFundsDto(accountNumber: String, amount: BigDecimal)

/**
  * A DTO for WithdrawFunds.
  */
case class WithdrawFundsDto(accountNumber: String, amount: BigDecimal)

/**
  * Json support for BankAccountHttpRoutes.
  */
trait BankAccountJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

  implicit val createBankAccountFormat = jsonFormat2(CreateBankAccount)
  implicit val depositFundsFormat = jsonFormat2(DepositFundsDto)
  implicit val withdrawFundsFormat = jsonFormat2(WithdrawFundsDto)

  implicit val startBankAccountTransactionFormat = jsonFormat2(StartBankAccountTransaction)
}

/**
  * Makes it easier to test this thing. Using this we can assert a known value for transaction id at test time
  * and randomly generate them at runtime.
  */
trait TransactionIdGenerator {
  def generateId: String
}

/**
  * Runtime, default impl for above trait.
  */
class TransactionIdGeneratorImpl extends TransactionIdGenerator {
  override def generateId: String = UUID.randomUUID().toString
}

/**
  * Http routes for bank account.
  */
trait BankAccountRoutes extends BankAccountJsonSupport {

  def bankAccountTransactionRegion: ActorRef
  def bankAccountRegion: ActorRef
  def transactionIdGenerator: TransactionIdGenerator = new TransactionIdGeneratorImpl

  implicit val system: ActorSystem
  implicit def timeout: Timeout
  implicit def ec: ExecutionContext = system.dispatcher

  val route: Route =
    path("bank-accounts") {
      post {
        entity(as[StartBankAccountTransaction]) { dto =>
          val start = StartBatchingTransaction(transactionIdGenerator.generateId, "Bank Account Transaction", dtoToDomain((dto)))
          implicit val timeout: Timeout = Timeout(10.seconds) // TODO: make configurable.
          onSuccess((bankAccountTransactionRegion ? start)) {
            case _ => complete(StatusCodes.Accepted, s"Transaction accepted with id: ${start.transactionId}")
          }
        }
      } ~
      post {
        entity(as[CreateBankAccount]) { cmd =>
          onSuccess((bankAccountRegion ? cmd)) {
            case _ => complete(StatusCodes.Accepted, s"CreateBankAccount accepted with number: ${cmd.accountNumber}")
          }
        }
      }
    }

  /**
    * Convert dto commands to list of domain commands.
    */
  private def dtoToDomain(dto: StartBankAccountTransaction): Seq[BankAccountTransactionalCommand] =
    (dto.deposits ++ dto.withdrawals).map {
      case d: DepositFundsDto => DepositFunds(d.accountNumber, d.amount)
      case w: WithdrawFundsDto => WithdrawFunds(w.accountNumber, w.amount)
    }
}
