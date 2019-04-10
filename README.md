# akka-transactional

## Implementation notes

This is an exploration of how to use akka cluster to perform in a transactional manner, providing ACID-like behavior,
using saga patterns.

The transaction itself is an Akka persistent actor, sharded across the cluster. The transaction will 
remain active until either all participating entity transactions are committed OR are rolled back due to 
any error or business exception.

Any business persistent actor participating in a transaction will essentially be "locked"
during the transaction, meaning that the actor may not participate in any other transaction until the initial 
one has completed. Stash is used for this.

Patterns used here are event sourcing of all business components, including the transaction.

The semantics are commands to entities result in events, that are wrapped in a transactional construct. Events on
a bank account appear as follows (in shorthand):

Commands
--------
StartEntityTransaction(tx1, DepositFunds(1000))
CommitTransaction(tx1)
StartEntityTransaction(tx2, WithdrawFunds(1500))
  
Resulting Events
----------------
EntityTransactionStarted(tx1, FundsDeposited(1000.00))
TransactionCleared(tx1, FundsDeposited(1000.00))
EntityTransactionStarted(tx2, InsufficientFunds(1000, 1500))

With the event structure above, it's up to the observer of the events to decide what is of interest. it is
most likely that only cleared transactions would be observed.

Bank accounts are provided as an example, but this pattern can work for anything, such as order to cash, something
like depleting inventory due to adding to a shopping cart and having the inventory made
available again just by rolling back due to shopping cart timeout or cancellation.

This is a useful pattern where strongly consistent designs would be used due to difficult use cases. For example, in
a trading system, "companies" may be modeled as transactional entities. Additionally there are constraints modeled
that apply across the companies. If "trade1" is made on any company and does not violate a constraint, it is cleared.
When "trade2" is made it violates a constraint and is reversed.

The alternative and go-to design would be to model the constraints at the top level, therefore having a bottleneck in
the system that all trades must go through, completely sacrificing availability.

## Items of note

BatchingTransactionalActor - This is the durable transaction that follows the "read your writes" pattern
to ensure the participating transactional entities have processed the commands. This implementation requires
all commands upon starting the transaction and probably not a good solution when there are very many commands.

StreamingTransactionalActor - This is a streaming implementation that starts a transaction on just and initial
command. Subsequent commands are received using the AddStreamingCommand command. The end of the stream is
indicated with the EndStreamingCommands command. Both AddStreamingCommand and EndStreamingCommands have a sequence
number and no gaps are allowed. This is used to detect a gap in commands, for now the design is to end the
transaction if a gap occurs.
    
NodeTaggedEventSubscription - A per node tagged event subscriber that receives events on behalf of the
entities and relays them to the correct transaction actor. For scalability purposes there is one of these per
node. In the case of a transaction that is re-instantiated or moved to another node, a transient event subscription
will be created on that new node to listen to the original node's tagged events. This transient subscription expires
after a configured time and can be restarted on demand from the transaction for retry etc.

TransactionalEntity - A trait that abstracts transactional behavior for any participating entity. There is very little
code to implement in order for an entity to participate in transactions.
  

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