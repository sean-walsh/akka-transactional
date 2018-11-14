package com.example

import com.example.PersistentSagaActor.TransactionalCommand

object PersistentSagaActorCommands {
  sealed trait PersistentSagaActorCommand

  case class StartSaga(transactionId: TransactionId, description: String, commands: Seq[TransactionalCommand])
    extends PersistentSagaActorCommand

  case object GetSagaState extends PersistentSagaActorCommand
}
