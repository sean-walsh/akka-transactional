package com.example

import com.example.PersistentSagaActor.{TransactionalCommand, TransactionalEventEnvelope}

object PersistentSagaActorEvents {

  sealed trait PersistentSagaActorEvent

  case class SagaStarted(transactionId: TransactionId, description: String, commands: Seq[TransactionalCommand])
    extends PersistentSagaActorEvent

  case class SagaPendingConfirmed(transactionId: TransactionId, entityId: EntityId) extends PersistentSagaActorEvent

  case class SagaExceptionConfirmed(transactionId: TransactionId, envelope: TransactionalEventEnvelope)
    extends PersistentSagaActorEvent

  case class SagaCommitConfirmed(transactionId: TransactionId, entityId: EntityId) extends PersistentSagaActorEvent

  case class SagaRollbackConfirmed(transactionId: TransactionId, entityId: EntityId) extends PersistentSagaActorEvent
}
