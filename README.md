# akka-transactional

## Implementation notes

This is an exploration of how to use akka cluster to perform in a transactional manner. Saga might be a misnomer
since this is really ACID-like behavior.

The saga itself is a long-running Akka persistent actor, sharded across the cluster. The saga will 
remain active until either all transactions are committed OR all transactions are rolled back due to 
any error or business exception.

Interestingly, any business persistent actor participating in a saga will essentially be "locked"
during the saga, meaning that the actor may not participate in any other sagas until the initial 
one has completed. Stash is used for this.

Patterns used here are event sourcing of all business components, including the sage as well as 
CQRS (just commands for now).

I used bank accounts as an example, but this pattern can work for anything, such as order to cash.

## The original use case

This is a use case I heard not once but twice in the financial sector. It involves a batch of bank
account transactions, in this case withdrawals and deposits. If any single one of the transactions
fail, the entire batch must fail. This ACID type transaction pattern works fully
within an Akka cluster and does not need Kafka, though it would be a nice addition
to add a Kafka option for integration across completely disparate systems. For
high performance applications, using the eventlog instead of Kafka should provide
near realtime performance.

## use case 2 --todo

Have completely separate functionality, such as anomalies cancel
a transaction within a given time window. It would be possible to co-locate
a security type application within the same cluster as the transactional
application(s) such as bank account for realtime inspection and interruption
of transactions. This can also optionally utilize Kafka instead of the event
log or in addition for nicely decoupled integration across services.

To support this a caller would initiate the bank account transactions. The application would then augment
the request to include anomaly detectors sharded by bank account number in the transaction.

## Deployment --todo

## GRPC support --todo