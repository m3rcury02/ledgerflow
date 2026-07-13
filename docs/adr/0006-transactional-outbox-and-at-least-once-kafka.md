# ADR 0006: Use a Transactional Outbox and Idempotent At-Least-Once Kafka Processing

- Status: Proposed
- Date: 2026-07-11
- Decision owners: LedgerFlow maintainers

## Context

PostgreSQL business data and a Kafka event cannot be atomically committed with a normal local transaction. Directly publishing before or after the database commit creates event-without-data or data-without-event failure windows. Kafka and consumer failures also produce redelivery.

The MVP explicitly requires at-least-once publication and consumption, bounded retries, a dead-letter topic, and one notification record.

## Decision

The capture-finalization PostgreSQL transaction inserts one immutable outbox row with:

- globally unique event ID;
- unique logical deduplication key for the payment-captured event;
- event type `com.ledgerflow.payment.captured` and version `1`;
- order ID Kafka partition key;
- versioned JSON data;
- correlation ID; and
- persisted W3C trace context.

A polling publisher claims due rows in short transactions using `FOR UPDATE SKIP LOCKED` and an owner token/lease. It publishes outside the claim transaction, waits for broker acknowledgement with `acks=all`, and marks the row published only with an owner-guarded update.

The outbox guarantee is at-least-once. Producer idempotence reduces Kafka-client retry duplicates but does not close the crash window between broker acknowledgement and the PostgreSQL published marker. Lease expiry republishes the same event ID.

The notification listener validates type/version, computes a canonical hash, and uses record processing with manual/controlled offset commit. One PostgreSQL transaction inserts an inbox row and one notification. A repeated event ID with the same hash is a successful no-op; the same ID with a different hash is an integrity failure.

Transient consumer failures use non-blocking retry topics after 1 second, 5 seconds, and 30 seconds: one initial attempt plus three retries, then `ledgerflow.payment-captured.v1.dlt`. Non-retryable schema/version/integrity failures go directly to the DLT. Retry and DLT publication must receive broker acknowledgement before the source offset commits.

A DLT catalog listener atomically stores original topic/partition/offset, payload hash/size, parsed identity/key/validated payload when available, and a sanitized failure with its operator failure record. Malformed bytes are not copied to PostgreSQL and are non-replayable. The DLT offset commits only after cataloging; database failure pauses/retries with alerting and no recursive DLT.

Operator replay republishes the same valid event ID/key/body, strips old retry/DLT/exception headers, adds failure/retry IDs and a new retry correlation/trace linked to the original, and resets delivery attempts. Broker acknowledgement marks the DLT row `REPLAYED`; only a committed or already-identical notification effect resolves it and the failed operation. Inbox constraints keep replay idempotent.

Non-blocking retry topics may reorder records. The tradeoff is accepted because the MVP emits one terminal payment-captured event per order and the consumer is idempotent.

Outbox publishing has a ten-attempt cycle: initial send plus nine retries after 1/2/4/8/16/32/64/128/256-second base delays with ±20% jitter. Exhaustion marks outbox failed and opens its operation atomically. An operator retry resets only cycle attempts/availability while retaining cumulative attempts/audit. It does not use Kafka's DLT because Kafka itself may be unavailable.

## Consequences

### Positive

- Committed business data always has a durable event to publish.
- Kafka and process failures do not require distributed transactions.
- Duplicate publication and consumption are safe and testable.
- Poison events stop blocking healthy records and remain inspectable.

### Costs and risks

- Publication is eventually consistent and can be duplicated.
- Polling, leases, retry topics, DLT cataloging, and replay require operational monitoring.
- Non-blocking retries sacrifice ordering.
- Stored event schemas and payload hashes require stable canonicalization/versioning.

## Alternatives considered

### Dual-write PostgreSQL and Kafka

Rejected because every ordering leaves an unrecoverable split-brain window.

### Kafka transactions or an exactly-once claim

Rejected because the business transaction is in PostgreSQL and consumer side effects are also PostgreSQL. Inbox idempotency states the end-to-end behavior more accurately.

### Change-data capture/Debezium

Deferred because it adds deployment infrastructure beyond the MVP. The outbox schema can support a future relay change.

### Infinite blocking retries

Rejected because poison records can starve partitions and hide failures from operators.

## References

- [Apache Kafka delivery design](https://kafka.apache.org/documentation/#design_deliverysemantics)
- [Spring Kafka non-blocking retry pattern](https://docs.spring.io/spring-kafka/reference/retrytopic/how-the-pattern-works.html)
- [Spring Kafka exception and dead-letter handling](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)
