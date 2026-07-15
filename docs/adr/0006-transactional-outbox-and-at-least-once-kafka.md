# ADR 0006: Use a Transactional Outbox and Idempotent At-Least-Once Kafka Processing

- Status: Accepted
- Date: 2026-07-11
- Accepted: 2026-07-13
- Decision owners: LedgerFlow maintainers
- Amended by: ADR 0011 (semantic notification identity) and ADR 0012 (terminal malformed-DLT evidence)

## Context

PostgreSQL business data and a Kafka record cannot be atomically committed with a normal local transaction. Publishing before or after the database commit creates an event-without-data or data-without-event failure window. Broker, process, and consumer failures also produce redelivery.

This milestone starts from a provider-confirmed payment. It does not implement public payment orchestration, order `COMPLETED`, or a final payment `CAPTURED` state.

## Decision

The existing capture-accounting `READ COMMITTED` transaction locks the payment and atomically:

1. creates or verifies the immutable balanced journal;
2. changes the payment to `CAPTURE_ACCOUNTED`; and
3. appends one immutable outbox row through `messaging.api`.

The event type is `com.ledgerflow.payment.captured`, schema version `1`; payment ID is the aggregate ID, order ID is the Kafka key, and the stable provider capture request UUID is causation. The canonical envelope contains exactly, in order, `eventId`, `eventType`, `schemaVersion`, `aggregateId`, `correlationId`, `causationId`, `occurredAt`, and `data`. A unique `payment-captured:{paymentId}` key makes a repeated append verify the original event instead of creating another.

A dedicated publisher supports multiple application instances. A short transaction claims due or expired rows with `SELECT ... FOR UPDATE SKIP LOCKED`, records an owner token and lease, and commits. Publication happens outside PostgreSQL. Only after `acks=all` does an owner-guarded transaction mark the row `PUBLISHED`.

One publication cycle has 10 total attempts. Failures use bounded exponential backoff starting at one second, capped at 256 seconds, with configurable jitter. Exhaustion leaves a durable `FAILED` row. A general outbox retry workflow is deferred.

The notification listener processes `ledgerflow.payment-captured.v1` with record acknowledgement. It validates the exact envelope, type/version, order-ID key, identity headers, money, and ID relationships. One PostgreSQL transaction inserts the event ID and canonical SHA-256 hash into `notification_inbox` and creates one notification. The same ID and hash is a successful no-op; the same ID with changed canonical content is an integrity failure. ADR 0011 adds an independent versioned semantic-effect identity so a new event ID cannot repeat the same capture notification.

Transient failures receive one initial attempt plus three bounded pause-based retries. Polling continues during backoff and intake is bounded by concurrency, poll count, and fetch bytes. An exhausted or non-retryable poison record is published to `ledgerflow.payment-captured.v1.dlt`; broker acknowledgement is required before the source offset advances. ADR 0009 records this resilience refinement.

The DLT listener catalogs bounded safe evidence by original topic/partition/offset. It stores validated canonical content/key only when replayable and never stores malformed raw bytes. ADR 0012 separately catalogs terminal invalid DLT input by its actual consumed DLT coordinates so malformed routing headers cannot block a partition indefinitely. The narrow `scripts/replay-dead-letter <dead-letter-uuid> <actor> <reason>` command leases one replayable row, preserves the envelope and key, removes old exception/delivery metadata, generates new transport correlation/trace context, waits for broker acknowledgement, and appends immutable replay audit records. It is not a general Kafka resend facility or an operator HTTP API.

## Delivery guarantees

- PostgreSQL payment `CAPTURE_ACCOUNTED`, ledger journal, and outbox append are atomic.
- Outbox-to-Kafka publication is at least once. A crash after broker acknowledgement and before the PostgreSQL marker can publish the same event again.
- Kafka consumption is at least once. A database commit followed by an offset-commit failure causes redelivery.
- Event-ID/hash inbox deduplication handles a stable event envelope; ADR 0011's semantic constraint handles the covered capture notification under a new event ID.
- LedgerFlow does not claim end-to-end exactly-once delivery.

## Consequences

Committed capture accounting always leaves durable publishable evidence, multiple publishers can work safely, and duplicate publication/consumption are expected and tested. The cost is eventual consistency, polling/lease state, bounded poison-message handling, and operational retention/inspection requirements. Blocking consumer retries temporarily pause that partition but are bounded and avoid extra retry topics.

## Alternatives considered

### Dual-write PostgreSQL and Kafka

Rejected because every ordering leaves a split-brain failure window.

### Kafka transactions or an end-to-end exactly-once claim

Rejected because the business transaction and consumer side effect are in PostgreSQL. Inbox idempotency states the actual guarantee more accurately.

### Change-data capture/Debezium

Deferred because it adds deployment infrastructure beyond this milestone. The outbox boundary permits a later relay replacement.

### Unbounded retries or arbitrary operator resend

Rejected because poison records can starve work and an unaudited resend can change identity or bypass validation.

## References

- [Apache Kafka delivery design](https://kafka.apache.org/documentation/#design_deliverysemantics)
- [Spring Kafka exception and dead-letter handling](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)
