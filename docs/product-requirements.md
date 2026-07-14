# LedgerFlow MVP Product Requirements

- Status: Partially implemented
- Last updated: 2026-07-13
- Related plan: `docs/plans/mvp-execplan.md`

## Purpose

The MVP demonstrates production backend engineering through one observable business flow:

```text
create order
  -> authorize payment
  -> capture payment
  -> create balanced ledger entries
  -> write transactional outbox event
  -> publish to Kafka
  -> consume event
  -> create notification record
```

The goal is not a feature-complete commerce product. The goal is to prove correctness under retries, partial failure, concurrency, duplicate delivery, and operator recovery.

## Current delivery status

The public Create Order slice is implemented. Authenticated clients can create one positive INR order in `CREATED` state and read an owned order; no public route starts payment. Non-public module slices implement provider authorization/capture, immutable capture accounting, and transactional outbox/Kafka notification delivery. Capture accounting atomically commits payment `CAPTURE_ACCOUNTED`, the balanced journal, and one outbox event. A leased publisher and idempotent consumer deliver one logical notification database effect with at-least-once semantics, and a narrow audited CLI can replay validated DLT records. Order `COMPLETED`, payment `CAPTURED`, public financial orchestration, and the operator HTTP workflow remain planned.

## Actors

- **API client** creates and reads its orders using an OAuth 2.0 bearer token.
- **Operator** inspects failed operations and requests controlled retries using a token with operator scopes.
- **Mock payment provider** exposes an external HTTP boundary for authorization, capture, lookup, latency, timeout, decline, and temporary-failure scenarios.
- **Outbox publisher** relays committed domain events from PostgreSQL to Kafka.
- **Notification consumer** consumes payment-captured events and creates notification records idempotently.

## Goals

- Create an order exactly once for a client-scoped idempotency key.
- Validate all order and payment state changes explicitly.
- Keep external payment calls outside database transactions.
- Atomically account a provider-confirmed capture by committing payment `CAPTURE_ACCOUNTED`, balanced ledger entries, and an outbox event in one PostgreSQL transaction.
- Publish and consume with at-least-once delivery while preventing duplicate business effects.
- Preserve correlation and distributed trace context across HTTP, asynchronous publication, Kafka retries, and consumption.
- Expose sanitized, auditable operator recovery without direct database or Kafka access.

## Non-goals

- Real card data, PCI DSS scope, or a real payment service provider.
- Inventory, fulfillment, tax, discounts, refunds, voids, disputes, or partial capture.
- Multiple payments per order or split tender.
- Multi-currency conversion. The MVP accepts `INR` only, represented as an ISO 4217 code.
- Sending email, SMS, or push messages. The consumer creates a notification record only.
- A customer or operator user interface.
- Building an identity provider, Kafka control plane, or production deployment platform.
- Exactly-once delivery claims across PostgreSQL and Kafka.

## Target end-to-end behavior

Steps 1–3 are active through the public API. Steps 4–6 and 10–12 are implemented behind non-public/module or background-worker boundaries. Steps 7–9 and the general operator API in step 13 require later milestones.

1. The client sends `POST /api/v1/orders` with a bearer token, `Idempotency-Key`, optional `X-Correlation-Id`, and a valid order request.
2. LedgerFlow validates authentication, payload, supported currency, idempotency-key syntax, and request ownership before creating data.
3. A unique client-and-operation-scoped idempotency record and order are created in PostgreSQL. A later milestone starts the payment workflow without changing the original Create Order replay result.
4. The payment module calls the mock provider through an outbound HTTP port. Authorization and capture use stable provider operation keys so uncertain calls can be queried and safely retried.
5. Normal success is processed synchronously. No PostgreSQL transaction remains open while waiting for the provider.
6. After confirmed capture, one PostgreSQL transaction changes payment to `CAPTURE_ACCOUNTED`, creates a balanced ledger transaction, and inserts one outbox event. It does not complete the order or introduce a final payment `CAPTURED` state.
7. A future orchestration milestone updates the public result and any final order/payment states without recreating the ledger or payment-captured event. A successful completed order remains a target behavior.
8. A terminal provider decline still creates the order and returns `201 Created` with `PAYMENT_DECLINED`; it creates no ledger or outbox rows.
9. Exhausted temporary failures or unresolved timeouts return `202 Accepted` with `PAYMENT_RETRY_PENDING`. The original response remains replayable while the current resource state is available through `GET /api/v1/orders/{orderId}`.
10. The outbox publisher claims due rows with a lease, publishes to Kafka, waits for broker acknowledgement, and marks them published. A crash can cause duplicate publication but not event loss.
11. The notification consumer inserts an inbox record and notification in one PostgreSQL transaction. An existing inbox event ID makes a duplicate delivery a successful no-op.
12. A transient consumer failure receives three bounded pause-based retries after the initial attempt and is then sent to a dead-letter topic. Polling continues during the delay and intake is bounded. A non-retryable schema, version, or integrity failure goes there without transient retries. A dead-letter listener catalogs safe, inspectable evidence.
13. The current narrow CLI can replay a validated catalog row with an actor, reason, and new transport correlation/trace while preserving the envelope and key. A secured operator inspection/retry HTTP workflow remains future work.

## Functional requirements

### Orders and HTTP idempotency

- **FR-001:** `POST /api/v1/orders` requires `Idempotency-Key`; a missing or malformed key returns `400` before creating business data.
- **FR-002:** Idempotency scope is the authenticated subject plus HTTP operation plus SHA-256 hash of the key. Raw keys are neither stored nor logged.
- **FR-003:** The request fingerprint is produced from the validated, normalized business fields. Correlation and tracing headers are excluded.
- **FR-004:** Repeating a completed request with the same scope, key, and fingerprint returns the original status, body, `Location`, and business identifiers without repeating payment, ledger, or outbox effects.
- **FR-005:** A replay uses the correlation ID and trace context of the replaying HTTP operation and adds `Idempotency-Replayed: true`; these transport headers are not part of the cached business result.
- **FR-006:** Reusing a key in the same scope with a different fingerprint returns `409 Conflict` with problem code `idempotency_key_reused`.
- **FR-007:** Concurrent matching requests cannot create two orders. PostgreSQL serializes conflicting key claims until the winning short transaction commits or rolls back; a matching contender then replays the result. Database timeouts produce a retryable `503`, and the client retries with the same key.
- **FR-008:** MVP idempotency records are retained indefinitely. A retention and archival policy requires a later ADR.
- **FR-009:** `GET /api/v1/orders/{orderId}` returns the current state only to its owning subject. Operators use the sanitized operations API rather than the customer order representation.

### Payment processing

- **FR-010:** Authorization and capture occur through a payment-provider port implemented by an HTTP adapter.
- **FR-011:** The local/test mock provider supports success, bounded latency, timeout-after-processing with initially unavailable reconciliation, authorization decline, one-shot temporary failure, and temporary failure that exhausts the automatic retry budget.
- **FR-012:** Provider requests carry stable authorization or capture operation keys. A timeout or unknown outcome is reconciled through provider lookup before any resend.
- **FR-013:** Declines are terminal and are not automatically retried.
- **FR-014:** Temporary failures receive at most one automatic retry after the initial attempt. Exhaustion creates a retryable failed operation and an authorization/capture retry-pending state. An unresolved timeout uses the corresponding explicit unknown state.
- **FR-015:** Every payment transition is checked against the state machine and guarded by optimistic concurrency. Invalid or stale transitions make no database change.
- **FR-016:** The application never holds a database transaction open across a provider HTTP call.

### Ledger and atomic outbox

- **FR-017:** A confirmed capture creates one immutable ledger transaction with exactly one `PAYMENT_CLEARING` debit and one `MERCHANT_PAYABLE` credit for the same positive INR amount.
- **FR-018:** Database constraints and a deferred constraint trigger reject an unbalanced, incomplete, mixed-currency, or non-positive ledger transaction at commit.
- **FR-019:** A unique capture reference prevents multiple ledger transactions for the same payment.
- **FR-020:** Payment `CAPTURE_ACCOUNTED`, ledger rows, and the payment-captured outbox event commit in one PostgreSQL transaction. This milestone does not change order state or add payment `CAPTURED`.
- **FR-021:** If that transaction fails, none of its local changes commit. Recovery reconciles the provider operation and repeats local finalization idempotently.

### Kafka and notifications

- **FR-022:** The outbox publisher provides at-least-once publication and never treats a Kafka send as successful before broker acknowledgement.
- **FR-023:** Events use a versioned envelope, globally unique event ID, order ID as Kafka key, UTC timestamp, correlation ID, and propagated W3C trace context.
- **FR-024:** The notification consumer provides at-least-once processing and deduplicates by event ID in PostgreSQL.
- **FR-025:** Inbox deduplication and notification creation commit atomically.
- **FR-026:** A transient processing failure receives three bounded pause-based retries after the initial attempt and then moves to `ledgerflow.payment-captured.v1.dlt`; non-retryable schema, version, and integrity failures go there without transient retries. Polling continues during backoff, intake is bounded, and DLT acknowledgement precedes the source offset commit.
- **FR-027:** A DLT record always retains original Kafka coordinates, payload hash/size, and sanitized failure metadata; it retains parsed event ID, key, and validated payload only when those values are valid. Failure to publish to the DLT does not commit the failed source offset.

### Correlation, tracing, and recovery

- **FR-028:** Every HTTP request, provider attempt, outbox attempt, Kafka record, consumer attempt, and operator retry has a valid correlation ID.
- **FR-029:** OpenTelemetry context propagates over inbound and outbound HTTP and through Kafka headers. Background work without a parent starts a new trace and links to the originating context when available.
- **FR-030:** Structured logs include correlation ID, trace ID, operation type, and non-sensitive resource identifiers.
- **FR-031:** Operators can list and inspect payment, outbox, and notification-consumption failures with an explicit retryable flag, without receiving stack traces, tokens, raw provider bodies, or secrets.
- **FR-032:** `POST /api/v1/operator/operations/{operationId}/retries` requires operator retry scope, an idempotency key, and an audit reason.
- **FR-033:** An operator retry is itself idempotent, audited, and safe under concurrent requests.

## Quality attributes

- **Correctness:** No successful capture can produce an unbalanced ledger transaction, duplicate ledger effect, missing outbox event, or duplicate notification.
- **Durability:** PostgreSQL is the source of truth for workflow state, idempotency, ledger, outbox, inbox, notifications, and failed operations.
- **Recoverability:** All unknown or retryable states are inspectable and have a defined automated or operator recovery path.
- **Security:** JWT validation, object ownership, operator scopes, input bounds, topic ACL assumptions, redaction, and secret handling follow `docs/threat-model.md`.
- **Observability:** A healthy flow is traceable from HTTP ingress through provider calls, outbox publication, Kafka consumption, and notification persistence.
- **Testability:** PostgreSQL, Kafka, and mock-provider behavior are exercised through real protocol boundaries in integration tests.

## Delivered milestone acceptance criteria

The `AC-M3` criteria record the delivered public Create Order slice; `AC-M5B` records the delivered non-public messaging slice. Statements about what Milestone 3 did not introduce are historical scope assertions, not claims that those capabilities are still absent.

- **AC-M3-001:** A valid scoped request returns `201`, a UUIDv7 `CREATED` order, positive INR minor units, UTC timestamps, `Location`, and a correlation ID.
- **AC-M3-002:** The same subject, key, and canonical payload returns the byte-equivalent original body and location with `Idempotency-Replayed: true`, without another order.
- **AC-M3-003:** The same scoped key with a changed canonical field returns `409 idempotency_key_reused` and does not add a row.
- **AC-M3-004:** Two concurrent identical requests create one order and one completed idempotency record; one response is the new result and one is its replay.
- **AC-M3-005:** Missing or malformed keys, invalid payloads, non-positive money, and unsupported currency are rejected without business data.
- **AC-M3-006:** GET returns the owned order and returns the same `404` shape for absent and non-owned IDs.
- **AC-M3-007:** PostgreSQL constraints enforce key uniqueness, SHA-256 lengths, positive INR money, valid state, completion consistency, and timestamp ordering.
- **AC-M3-008:** No payment, provider, ledger, outbox, Kafka, notification, or operator behavior is introduced.

- **AC-M5B-001:** One capture-accounting transaction commits payment `CAPTURE_ACCOUNTED`, the balanced journal, and exactly one matching payment-captured outbox row, or rolls them all back.
- **AC-M5B-002:** The canonical envelope fields are exactly `eventId`, `eventType`, `schemaVersion`, `aggregateId`, `correlationId`, `causationId`, `occurredAt`, and `data`; causation is the stable capture request UUID.
- **AC-M5B-003:** Multiple publishers safely claim with `SKIP LOCKED`; publication occurs outside the claim transaction and `PUBLISHED` is owner-guarded after broker acknowledgement.
- **AC-M5B-004:** A crash after Kafka acknowledgement but before the outbox marker can republish the same event ID, and the inbox still permits only one logical notification database effect.
- **AC-M5B-005:** One initial consumer attempt plus three bounded pause-based retries precede acknowledged DLT publication; intake remains bounded, and validated poison records are cataloged and replayable through an audited CLI.
- **AC-M5B-006:** Kafka uses W3C trace headers and a validated correlation header. Replay preserves the business envelope/key while starting new transport correlation/trace context.
- **AC-M5B-007:** Delivery is documented as atomic PostgreSQL business-plus-outbox and at-least-once publish/consume, never as end-to-end exactly once.

## Target full-flow acceptance criteria

The following remain proposed for later milestones:

- **AC-001:** A valid success request returns `201`, a `COMPLETED` order, a `CAPTURED` payment, two balanced ledger entries, and one pending or published outbox event.
- **AC-002:** Replaying AC-001 with the same key and semantically identical payload returns the original status and body with `Idempotency-Replayed: true`; row counts and provider-call counts do not increase.
- **AC-003:** Reusing the key with any changed business field returns `409 idempotency_key_reused` and creates no additional business rows.
- **AC-004:** Two concurrent identical requests result in exactly one order, one payment workflow, and one eventual cached result.
- **AC-005:** The authorization-decline scenario reaches terminal payment `DECLINED` and order `PAYMENT_DECLINED`, with no capture, ledger, or outbox effect.
- **AC-006:** Latency succeeds within the configured provider timeout; temporary failure retries once; unresolved timeout or exhausted temporary failure returns `202 PAYMENT_RETRY_PENDING` and creates an inspectable operation.
- **AC-007:** Every invalid payment or order transition is rejected by unit tests, and stale concurrent updates are rejected by optimistic locking.
- **AC-008:** Direct attempts to commit unbalanced, incomplete, non-positive, or mixed-currency ledger rows fail at the database boundary.
- **AC-009:** Fault injection at every capture-accounting statement proves payment `CAPTURE_ACCOUNTED`, ledger entries, and outbox event are all committed or all rolled back. Later order/payment finalization must not recreate those effects.
- **AC-010:** A publisher crash after Kafka acknowledgement but before marking the outbox row can produce a duplicate event, and the consumer still creates one notification.
- **AC-011:** A transient consumer failure receives one initial attempt plus exactly three bounded pause-based retries before DLT; polling continues with bounded intake, and non-retryable schema, version, or integrity failures go to DLT without transient retries. The catalog is inspectable now; operator HTTP visibility remains future work.
- **AC-012:** Repeating an operator retry command does not schedule duplicate work; a successful retry resolves the failed operation and preserves the original business/event identifier.
- **AC-013:** Trace tests show connected HTTP client/server spans, provider spans, Kafka producer spans, and Kafka consumer processing spans. Correlation IDs appear in responses, event headers, and structured logs.
- **AC-014:** Customer tokens cannot read another subject's order, and customer tokens cannot call operator endpoints.
- **AC-015:** `./gradlew clean verify` passes with unit, PostgreSQL Testcontainers, Kafka Testcontainers, architecture, OpenAPI, documentation, static-analysis, and formatting checks.
- **AC-016:** A malformed or contradictory provider response durably creates order/payment `FAILED`, returns a replayable `502 provider_protocol_error` with the order location, and creates no ledger or outbox effect.

## Product assumptions

- The public API is versioned under `/api/v1`; backward-compatibility policy beyond v1 requires a later ADR.
- Only INR and one full capture are supported in the MVP.
- The accounting entry represents captured funds owed to a merchant; the MVP does not assert that LedgerFlow is merchant-of-record or recognize revenue.
- The caller identity is the JWT `sub` claim; no `customerId` is accepted from the request body.
- The identity provider, Kafka cluster, PostgreSQL service, and telemetry backend are external platform concerns.
- All timing, retry, lease, and batch values are configurable; tests override delays without changing attempt counts.
