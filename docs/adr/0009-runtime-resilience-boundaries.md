# ADR 0009: Define Runtime Resilience Boundaries

- Status: Accepted
- Date: 2026-07-14

## Context

LedgerFlow calls an external payment provider, relays a PostgreSQL outbox to Kafka, and consumes Kafka at least once. These paths must remain bounded during latency, partial network failure, overload, broker/database loss, and process shutdown. A timeout cannot prove that a payment provider did not apply an operation, and retries must not bypass the payment state machine or stable provider request IDs.

The existing JDK HTTP adapter supplied a connection and request deadline, and Kafka retry used bounded attempts, but there was no provider circuit/concurrency isolation, cross-adapter graceful drain, explicit readiness dependency model, pause-based consumer backpressure, or production guard for controlled faults.

## Decision

1. The JDK HTTP adapter uses separate positive connect, response/read, and overall deadlines. The overall deadline cancels the asynchronous future. A response timeout or post-send I/O loss is an unknown outcome and enters lookup-first recovery with the persisted request ID.
2. Payment retry classification remains explicit application logic. Only `TemporaryFailure` retries, using the existing bounded exponential backoff with jitter. Confirmed declines, unknown outcomes, invalid responses, and concurrency conflicts do not retry.
3. Use Resilience4j 2.4.0 direct core circuit-breaker and semaphore-bulkhead APIs around the `PaymentProvider` port. Do not use its Spring/AOP starter or retry module. Confirmed declines are availability successes; temporary, unknown, and invalid availability results count toward the circuit. The bulkhead has no waiting queue.
4. The existing `operations` feature owns a minimal `operations.api` in-flight work tracker and allowlisted fault hook. Provider calls, outbox batches, and notification handling register work. Shutdown stops new admission and drains within a bounded phase; timeout is logged and exposed as failed drain state.
5. Liveness reports only internal process state. Readiness includes application readiness, PostgreSQL, enabled Kafka-adapter connectivity, and drain state. PostgreSQL is startup-critical. Kafka is startup-checked when Kafka adapters are enabled; its failure does not mutate durable outbox data.
6. Kafka consumer intake is bounded by typed concurrency and `max.poll.records`. Retry delays use Spring Kafka container pausing so polling continues, record acknowledgment remains per record, and DLT publication remains broker-acknowledged before source advancement. Listener and scheduler shutdown deadlines are explicit.
7. Controlled fault injection is disabled by default, limited to an enum allowlist and ten-second maximum delay, and configurable only with `local`, `test`, or `integration-test` active. Startup rejects enabled injection outside those profiles.
8. Toxiproxy Testcontainers tests cover latency, connection reset, timeout, temporary PostgreSQL loss, and temporary Kafka loss. Unit/integration tests retain evidence for retry limits, circuit recovery, bulkhead admission, duplicate side-effect prevention, and graceful drain.

## Consequences

- Repeated provider or overload failures fail fast without unbounded threads or queues, while provider business declines remain normal domain outcomes.
- Unknown provider outcomes still require reconciliation; the circuit breaker is not permission to resend with a new ID.
- Provider, publisher, and consumer modules acquire a narrow dependency on `operations.api`, but business rules and recovery remain feature-owned and module dependencies stay acyclic.
- Readiness may be down during a dependency outage while liveness remains up, preventing restart loops for recoverable external failure.
- At-least-once duplicates remain possible. PostgreSQL uniqueness, outbox ownership, and inbox event-ID/hash checks remain the side-effect controls; this ADR makes no exactly-once end-to-end claim.
- Resilience4j is one manually versioned production dependency family and must be included in license, vulnerability, and upgrade review.

## Alternatives considered

- A custom circuit breaker and semaphore were rejected because correctness under concurrent state transitions and half-open recovery is not application-specific value.
- Generic annotation/AOP retries were rejected because they obscure outcome classification and could retry a decline or unknown payment outcome.
- Blocking the Kafka consumer thread for retry delays was rejected because long delays risk partition starvation and consumer-group churn.
- Putting external dependencies in liveness was rejected because restarting cannot repair PostgreSQL, Kafka, or provider outages.
- Unbounded shutdown waiting was rejected because deployments require a termination limit; a bounded timeout must instead emit explicit failure evidence for recovery.
