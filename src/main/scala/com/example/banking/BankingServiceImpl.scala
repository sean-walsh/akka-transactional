package com.example.banking

import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.Logging
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.Timeout
import com.example.banking.BankAccountCommands.{DepositFunds, WithdrawFunds}
import com.example.banking.grpc._
import com.lightbend.transactional.PersistentTransactionCommands.{AddStreamingCommand, EndStreamingCommands, StartTransaction}
import com.lightbend.transactional.PersistentTransactionalActor
import com.lightbend.transactional.PersistentTransactionalActor.Ack

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class BankingServiceImpl(system: ActorSystem) extends BankingService {

  implicit val ec: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = Timeout(10.seconds)

  val log = Logging.getLogger(system, this)

  final val transactionRegion = s"/user/${PersistentTransactionalActor.RegionName}"

  override def bankAccountTransaction(in: BankAccountTransactionRequest): Future[BankAccountTransactionReply] = {
    val transactionId = UUID.randomUUID().toString
    val command = StartTransaction(transactionId, "bank-account-transaction")
    (system.actorSelection(transactionRegion) ? command).mapTo[Ack].recover {
      case _: Throwable => log.info(s"Could not deliver $command to $transactionRegion.")
    }.map(_ => BankAccountTransactionReply(transactionId))
  }

  override def addBankingCommand(in: Source[AddBankingCommandRequest, NotUsed]): Source[AddBankingCommandReply, NotUsed] = {
    in.map { request =>
      val command =
        if (request.commandType.toLowerCase() == "deposit")
          DepositFunds(request.bankAccount, BigDecimal(request.amount))
        else if (request.commandType.toLowerCase() == "deposit")
          WithdrawFunds(request.bankAccount, BigDecimal(request.amount))
        else throw new Exception(s"Invalid command type, use 'deposit' or 'withdrawal")

      val add = AddStreamingCommand(request.transactionId, command, request.sequence)
      (system.actorSelection(transactionRegion) ? add).mapTo[Ack].recover {
        case _: Throwable => log.info(s"Could not deliver $command to $transactionRegion.")
      }

      AddBankingCommandReply("accepted")
    }
  }

  override def endStreamingCommands(in: EndStreamingCommandsRequest): Future[EndStreamingCommandsReply] = {
    val command = EndStreamingCommands(in.transactionId, in.sequence)
    (system.actorSelection(transactionRegion) ? command).mapTo[Ack].recover {
      case _: Throwable => log.info(s"Could not deliver $command to $transactionRegion.")
    }.map(_ => EndStreamingCommandsReply("accepted"))
  }
}
