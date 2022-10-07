package com.akkatransactional.core

import com.akkatransactional.core.TransactionalEntityCommands._
import com.akkatransactional.core.TransactionalEntityEvents._

sealed private[core] trait TransactionState {
  def transactionId: String
}

final private[core] case class EmptyState(transactionId: String) extends TransactionState

final private[core] case class EntityTypeAndId(entityType: String, entityId: String)

/**
  * In this state, the entity commands are streamed in and sent to the entities until the end of commands message.
  */
final private[core] case class PendingState(
                                       transactionId: String,
                                       sequence: Int = 0,
                                       outOfSequenceError: Boolean = false,
                                       commandsEnded: Boolean = false,
                                       // the following structures may need to be spread out among distributed child actors, if transactions are very large.
                                       entities: Seq[EntityTypeAndId] = Nil,
                                       confirmed: Seq[EntityTypeAndId] = Nil,
                                       exceptions: Seq[EntityTypeAndId] = Nil
                                     ) extends TransactionState {
  // Add incoming command to current state.
  def withCommandAdded(event: CommandAdded): PendingState =
    if (isValidSequence(event.sequence))
      copy(entities = entities :+ EntityTypeAndId(event.entityType, event.entityId),
        sequence = event.sequence)
    else
      copy(entities = entities :+ EntityTypeAndId(event.entityType, event.entityId),
        outOfSequenceError = true)

  // Proof that the entity has check-pointed the event
  def withEntityEventConfirmed(event: EntityEventConfirmed): PendingState =
    confirmed.find(p => p.entityType == event.entityEvent.entityType
      && p.entityId == event.entityEvent.entityId) match {
      case Some(_) =>
        this
      case None =>
        copy(confirmed = confirmed :+ EntityTypeAndId(event.entityEvent.entityType, event.entityEvent.entityId))
    }

  // Error in processing entity pending event todo
  def withEntityErrorConfirmed(event: EntityErrorEvent): PendingState =
    confirmed.find(p => p.entityType == event.entityType
      && p.entityId == event.entityId) match {
      case Some(_) =>
        this // todo
      case None =>
        this //todo
    }

  // Apply commands ended event, ensuring it is in sequence, return one of the 3 active states
  def withCommandsEnded(ended: CommandsEnded): TransactionState = {
    val pendingWithCommandsEnded = this.copy(commandsEnded = true)

    val pending =
      if (!isValidSequence(ended.sequence))
        this.copy(outOfSequenceError = true)
      else
        pendingWithCommandsEnded

    if (pending.isCommitCondition())
      CommitState(transactionId)
    else if (pending.isRollbackCondition())
      RollbackState(transactionId)
    else
      pending
  }

  // Check the incoming sequence number is in order, meaning nothing was dropped
  def isValidSequence(sequence: Long): Boolean =
    if (this.sequence == 0 && sequence == 1 || (this.sequence - sequence == 1))
      true
    else false

  // Commit if no errors, commands no longer streaming in and no out of sequence error
  def isCommitCondition(): Boolean = {
    if (commandsEnded && entities.size == confirmed.size && !outOfSequenceError && exceptions.size == 0)
      true
    else false
  }

  // Rollback immediately with any error and do not send further transaction commands
  def isRollbackCondition(): Boolean =
    if (exceptions.size > 0)
      true
    else false

  // Send the entity command, but only once this transactional entity has journaled.
  def sendStartTransaction(entityLocator: EntityLocator, entityType: String, entityId: String,
                           command: EntityTransactionalCommand): Unit =
    entityLocator.sendCommand(StartEntityTransaction(transactionId, entityType, entityId, command))

  // Start commit or rollback if possible
  def sendSideEffectsMaybe(entityLocator: EntityLocator): Unit =
    if (isCommitCondition())
      entities.foreach(e =>
        entityLocator.sendCommand(CommitEntityTransaction(transactionId, e.entityType, e.entityId))
      )
    else if (isRollbackCondition()) {
      entities.foreach(e =>
        // It's ok to send to entities that previously emitted an error, the rollback will be ignored.
        entityLocator.sendCommand(RollbackEntityTransaction(transactionId, e.entityType, e.entityId))
      )
    }
}

  /**
    * In the commit state, the commit commands have all been sent to the entities and we await confirmation.
    */
  final private[core] case class CommitState(
                                        transactionId: String,
                                        sent: Seq[EntityTypeAndId] = Nil,
                                        confirmed: Seq[EntityTypeAndId] = Nil) extends TransactionState {

    def withClearingConfirmed(event: EntityClearingEvent): CommitState =
      sent.find(p => p.entityType == event.entityType
        && p.entityId == event.entityId) match {
        case Some(_) =>
          copy(confirmed = confirmed :+ EntityTypeAndId(event.entityType, event.entityId))
        case None =>
          this
      }

    def hasConfirmed(entityTypeAndId: EntityTypeAndId): Boolean =
      confirmed.exists(p => p.entityType == entityTypeAndId.entityType && p.entityId == entityTypeAndId.entityId)

    def canComplete(): Boolean =
      if (sent.size == confirmed.size)
        true
      else false
  }

  /**
    * In the rollback state, the rollback commands have all been sent to the entities and we await confirmation.
    */
  final private[core] case class RollbackState(
                                          transactionId: String,
                                          sent: Seq[EntityTypeAndId] = Nil,
                                          confirmed: Seq[EntityTypeAndId] = Nil) extends TransactionState {

    def canComplete: Boolean =
      if (sent.size == confirmed.size)
        true
      else false
  }

  /**
    * Transaction complete and all entity transactions have cleared.
    */
  final private[core] case class ClearedComplete(transactionId: String) extends TransactionState

  /**
    * Transaction complete and all entity transactions have rolled back.
    */
  final private[core] case class RolledBackComplete(transactionId: String) extends TransactionState
