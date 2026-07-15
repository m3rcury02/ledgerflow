# LedgerFlow MVP Product Requirements

- Status: Partially implemented
- Last updated: 2026-07-15
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

The complete customer order workflow is implemented. An authenticated create request persists one
order and payment identity, performs authorization and capture through the external mock-provider
boundary, reconciles unknown outcomes by the same stable operation ID, posts one balanced journal
and one logical outbox event, and returns the durable order/payment outcome. Provider calls occur
outside PostgreSQL transactions. Capture accounting atomically commits payment
`CAPTURE_ACCOUNTED`, the journal, and outbox event; a following short local transaction finalizes
payment `CAPTURED`, order `COMPLETED`, and the idempotency snapshot. Kafka publication and
notification remain asynchronous and at least once. Transport and semantic idempotency still
produce one logical notification effect. Invalid DLT evidence and the narrow audited replay CLI are
implemented. End-to-end tracing, bounded business/runtime metrics, structured correlated logs,
provisioned dashboards, provisional SLOs, and alert runbooks are implemented; the secured operator
HTTP workflow remains planned.

## Actors

- **API client** creates and reads its orders using an OAuth 2.0 bearer token.
- **Operator (planned Milestone 7B)** inspects failed operations and requests controlled retries using a token with operator scopes.
- **Mock payment provider** exposes an external HTTP boundary for authorization, capture, lookup, latency, timeout, decline, and temporary-failure scenarios.
- **Outbox publisher** relays committed domain events from PostgreSQL to Kafka.
- **Notification consumer** consumes payment-captured events and creates notification records idempotently.

## Goals

- Create an order exactly once for a client-scoped idempotency key.
- Validate all order and payment state changes explicitly.
- Keep external payment calls outside database transactions.
- Atomically account a provider-confirmed capture by committing payment `CAPTURE_ACCOUNTED`, balanced ledger entries, and an outbox event in one PostgreSQL transaction, then finalize `CAPTURED`/`COMPLETED` in a separate guarded local transaction.
- Publish and consume with at-least-once delivery while preventing duplicate business effects.
- Preserve correlation and distributed trace context across HTTP, asynchronous publication, Kafka retries, and consumption.
- Preserve the identities and immutable evidence needed for a later sanitized, auditable operator recovery path without direct database or Kafka manipulation.

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

Steps 1–12 are implemented. Steps 1–9 form the synchronous public command and durable local result; steps 10–12 remain asynchronous. The general operator API in step 13 requires a later milestone.

1. The client sends `POST /api/v1/orders` with a bearer token, `Idempotency-Key`, optional `X-Correlation-Id`, and a valid order request.
2. LedgerFlow validates authentication, payload, supported currency, idempotency-key syntax, and request ownership before creating data.
3. One short PostgreSQL transaction creates the scoped in-progress idempotency record, `PAYMENT_PROCESSING` order, and payment with its stable authorization operation ID. It commits before provider I/O.
4. The payment module calls the mock provider through an outbound HTTP port. Authorization and capture use stable provider operation keys so uncertain calls can be queried and safely retried.
5. Normal success is processed synchronously. No PostgreSQL transaction remains open while waiting for the provider.
6. After confirmed capture, one PostgreSQL transaction changes payment to `CAPTURE_ACCOUNTED`, creates a balanced ledger transaction, and inserts one logical outbox event.
7. A separate short local transaction changes the accounted payment to `CAPTURED`, the order to `COMPLETED`, and the idempotency record to its immutable `201` result. Deferred constraints require the journal and outbox evidence. Neither provider nor Kafka I/O occurs in this transaction.
8. A terminal provider decline still creates the order and returns `201 Created` with `PAYMENT_DECLINED`; it creates no ledger or outbox rows.
9. Exhausted temporary failures or unresolved timeouts return `202 Accepted` with `PAYMENT_RETRY_PENDING`. The original response remains replayable while the current resource state is available through `GET /api/v1/orders/{orderId}`.
10. The outbox publisher claims due rows with a lease, publishes to Kafka, waits for broker acknowledgement, and marks them published. A crash can cause duplicate publication but not event loss.
11. The notification consumer inserts an inbox record and notification in one PostgreSQL transaction. An existing event ID and matching hash is a transport no-op. A new event ID for the same versioned capture effect records a semantic-duplicate inbox outcome without another notification; conflicting content is an integrity failure.
12. A transient consumer failure receives three bounded pause-based retries after the initial attempt and is then sent to a dead-letter topic. Polling continues during the delay and intake is bounded. A non-retryable schema, version, or integrity failure goes there without transient retries. A dead-letter listener catalogs validated safe evidence; terminal invalid DLT input advances only after sanitized evidence using actual DLT coordinates is durable.
13. The current narrow CLI can replay a validated catalog row with an actor, reason, and new transport correlation/trace while preserving the envelope and key. A secured operator inspection/retry HTTP workflow remains future work.

## Functional requirements

### Orders and HTTP idempotency

- **FR-001:** `POST /api/v1/orders` requires `Idempotency-Key`; a missing or malformed key returns `400` before creating business data.
- **FR-002:** Idempotency scope is the authenticated subject plus HTTP operation plus SHA-256 hash of the key. Raw keys are neither stored nor logged.
- **FR-003:** The request fingerprint is produced from the validated, normalized business fields. Correlation and tracing headers are excluded.
- **FR-004:** Repeating a completed request with the same scope, key, and fingerprint returns the original status, body, `Location`, and business identifiers without repeating payment, ledger, or outbox effects.
- **FR-005:** A replay uses the correlation ID and trace context of the replaying HTTP operation and adds `Idempotency-Replayed: true`; these transport headers are not part of the cached business result.
- **FR-006:** Reusing a key in the same scope with a different fingerprint returns `409 Conflict` with problem code `idempotency_key_reused`.
- **FR-007:** Concurrent matching requests cannot create two orders. PostgreSQL serializes the initial key claim; matching contenders resume or replay the same durable order/payment. Optimistic payment versions, guarded order transitions, and database uniqueness make resumptions converge. A still-active provider call returns a retryable `503` rather than initiating a second call.
- **FR-008:** MVP idempotency records are retained indefinitely. A retention and archival policy requires a later ADR.
- **FR-009:** `GET /api/v1/orders/{orderId}` returns the current state only to its owning subject. Operators use the sanitized operations API rather than the customer order representation.

### Payment processing

- **FR-010:** Authorization and capture occur through a payment-provider port implemented by an HTTP adapter.
- **FR-011:** The local/test mock provider supports success, bounded latency, timeout after confirmed processing, timeout with lookup `NOT_FOUND` and safe same-ID resend, authorization/capture decline, one-shot temporary failure, and temporary failure that exhausts the automatic retry budget.
- **FR-012:** Provider requests carry stable authorization or capture operation keys. A timeout or unknown outcome is reconciled through provider lookup before any resend.
- **FR-013:** Declines are terminal and are not automatically retried.
- **FR-014:** Temporary failures receive at most one automatic retry after the initial attempt. Exhaustion creates an authorization/capture retry-pending state; the general failed-operation projection remains Milestone 7B work. An unresolved timeout uses the corresponding explicit unknown state.
- **FR-015:** Every payment transition is checked against the state machine and guarded by optimistic concurrency. Invalid or stale transitions make no database change.
- **FR-016:** The application never holds a database transaction open across a provider HTTP call.

### Ledger and atomic outbox

- **FR-017:** A confirmed capture creates one immutable ledger transaction with exactly one `PAYMENT_CLEARING` debit and one `MERCHANT_PAYABLE` credit for the same positive INR amount.
- **FR-018:** Database constraints and a deferred constraint trigger reject an unbalanced, incomplete, mixed-currency, or non-positive ledger transaction at commit.
- **FR-019:** A unique capture reference prevents multiple ledger transactions for the same payment.
- **FR-020:** Payment `CAPTURE_ACCOUNTED`, ledger rows, and the payment-captured outbox event commit in one PostgreSQL transaction. A later short local transaction finalizes payment `CAPTURED`, order `COMPLETED`, and the HTTP idempotency result without recreating those effects.
- **FR-021:** If that transaction fails, none of its local changes commit. Recovery reconciles the provider operation and repeats local finalization idempotently.

### Kafka and notifications

- **FR-022:** The outbox publisher provides at-least-once publication and never treats a Kafka send as successful before broker acknowledgement.
- **FR-023:** Events use a versioned envelope, globally unique event ID, order ID as Kafka key, UTC timestamp, correlation ID, and propagated W3C trace context.
- **FR-024:** The notification consumer provides at-least-once processing. Event ID plus canonical hash supplies transport idempotency; a database-unique, versioned semantic identity based on the immutable capture ledger transaction prevents re-enveloping from repeating the notification effect and detects conflicting content.
- **FR-025:** Inbox deduplication and notification creation commit atomically.
- **FR-026:** A transient processing failure receives three bounded pause-based retries after the initial attempt and then moves to `ledgerflow.payment-captured.v1.dlt`; non-retryable schema, version, and integrity failures go there without transient retries. Polling continues during backoff, intake is bounded, and DLT acknowledgement precedes the source offset commit.
- **FR-027:** A validated DLT record retains original Kafka coordinates, payload hash/size, and sanitized failure metadata; it retains parsed event ID, key, and canonical payload only when those values are valid. Missing or malformed original routing, empty/oversized payload, or an invalid event creates immutable sanitized terminal evidence using actual DLT coordinates. Failure to publish to the DLT or persist terminal evidence does not commit the corresponding offset.

### Correlation, tracing, and recovery

- **FR-028:** Every HTTP request, provider attempt, outbox attempt, Kafka record, consumer attempt, and operator retry has a valid correlation ID.
- **FR-029:** OpenTelemetry context propagates over inbound and outbound HTTP and through Kafka headers. Durable asynchronous work restores a trustworthy stored causal parent; independent future recovery starts a new trace and links to the stored origin rather than asserting false parentage.
- **FR-030:** Structured logs include safe correlation and trace identifiers plus stable event, action, outcome, and error codes. They omit customer/resource identifiers unless an approved diagnostic need and bounded retention justify them.
- **FR-031:** Operators can list and inspect payment, outbox, and notification-consumption failures with an explicit retryable flag, without receiving stack traces, tokens, raw provider bodies, or secrets.
- **FR-032:** `POST /api/v1/operator/operations/{operationId}/retries` requires operator retry scope, an idempotency key, and an audit reason.
- **FR-033:** An operator retry is itself idempotent, audited, and safe under concurrent requests.

### Security and abuse resistance

- **FR-034:** Active order routes require an RS256 JWT with exact configured issuer, `ledgerflow-api` audience, valid time claims, route scope, and `customer` or `admin` realm role.
- **FR-035:** Order reads constrain both order ID and JWT subject and return an indistinguishable `404` for absent and differently owned resources. An `operator` role alone grants no customer-object access.
- **FR-036:** Create Order accepts only bounded JSON without query parameters or compressed bodies and rejects duplicate/unknown properties, unsupported media, excessive structure, and oversized content before a business write.
- **FR-037:** Each application instance bounds create attempts and tracked identities per subject hash and returns `429` with `Retry-After`; a production ingress provides aggregate and unauthenticated volumetric controls.
- **FR-038:** API problems, logs, traces, persistence, events, and DLT evidence exclude bearer tokens, idempotency keys, request bodies, secret configuration, real payment credentials, PANs, and CVVs.
- **FR-039:** Secrets come only from environment/secret injection. A pinned repeatable scan checks committed content/configuration, packaged Java dependencies, and all Compose images; fixed HIGH/CRITICAL findings require remediation or an approved owned, expiring exception.
- **FR-040:** Every privileged replay mutation has immutable actor, reason, action, correlation, and timestamp evidence; PostgreSQL rejects audit update and delete.

## Quality attributes

- **Correctness:** No successful capture can produce an unbalanced ledger transaction, duplicate ledger effect, missing outbox event, or duplicate notification for the same versioned semantic effect.
- **Durability:** PostgreSQL is the source of truth for workflow state, idempotency, ledger, outbox, inbox, notifications, and failed operations.
- **Recoverability:** All unknown or retryable states are inspectable and have a defined automated or operator recovery path.
- **Security:** JWT validation, object ownership, operator scopes, input bounds, topic ACL assumptions, redaction, and secret handling follow `docs/threat-model.md`.
- **Observability:** A healthy flow is traceable from HTTP ingress through provider calls, outbox publication, Kafka consumption, and notification persistence.
- **Testability:** PostgreSQL, Kafka, and mock-provider behavior are exercised through real protocol boundaries in integration tests.

## Delivered milestone acceptance criteria

The `AC-M3` criteria record the historical Create Order slice, `AC-M5B` records messaging, `AC-M5C` records security hardening, `AC-M5D` records abuse-case remediation, `AC-M6` records the complete public workflow, and `AC-M7A` records end-to-end observability. Historical scope exclusions are not claims that those capabilities are still absent.

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

- **AC-M5C-001:** A correctly signed token with exact issuer/audience/time claims, route scope, and `customer`/`admin` role reaches the order use case; wrong signature/issuer/audience, expiry, scope, or role fails safely.
- **AC-M5C-002:** Two subjects cannot read each other's orders, operator-only identity cannot read customer orders, and customers cannot cross the reserved operator boundary.
- **AC-M5C-003:** Success and problem responses carry secure headers; HSTS is present only on HTTPS.
- **AC-M5C-004:** Unexpected query input, compressed/unsupported bodies, duplicate/unknown JSON, and payloads above the configured limit fail before any business row is inserted.
- **AC-M5C-005:** The configured per-subject attempts are admitted to normal processing and the next receives `429`, `Retry-After`, and correlation ID without a business effect; tracked state is bounded and hashed.
- **AC-M5C-006:** The local realm declares customer/operator/admin roles, order/operation scopes, and API audience without users, passwords, or client secrets.
- **AC-M5C-007:** Sensitive marker tests show rejected request/key material absent from API responses, captured logs, and order/idempotency persistence; direct SQL audit update/delete fails.
- **AC-M5C-008:** The pinned scan finds no committed secret or fixed HIGH/CRITICAL application-dependency vulnerability and no unapproved, stale, changed, or expired Compose-image finding; any accepted Compose tuple is exact, visible, expiring, and local-development-only.

- **AC-M5D-001:** The application listener serves no Actuator path; a distinct configurable management listener serves status-only liveness/readiness and management-network-only Prometheus, with no public management ingress contract.
- **AC-M5D-002:** One bounded readiness snapshot is shared by concurrent callers, success and failure expire, startup is uncached, and one lifecycle-managed Kafka Admin is reused rather than allocated per request.
- **AC-M5D-003:** Same-envelope redelivery remains transport-idempotent; a new event ID with matching semantic identity/content creates one semantic-duplicate inbox row and no second notification, while a semantic content conflict fails without a second effect.
- **AC-M5D-004:** Terminal malformed DLT input stores one immutable sanitized row by actual DLT coordinates before offset advancement. Evidence-store failure retains the offset, and recovery permits later partition records to progress without duplicate evidence.
- **AC-M5D-005:** V006 and V007 upgrade compatible V005 evidence without deletion; unmappable or semantically duplicated legacy data fails closed for explicit reconciliation.

- **AC-M6-001:** A valid authenticated request returns `201`, order `COMPLETED`, payment `CAPTURED`, one balanced capture journal, and one pending or published logical outbox event without waiting for Kafka or notification completion.
- **AC-M6-002:** Identical replay returns the original business result and location; changed payload returns `409`; concurrent requests/resumptions use one order, payment, provider authorization/capture identity, journal, and logical outbox event.
- **AC-M6-003:** Authorization/capture decline, bounded temporary failure, lookup-confirmed timeout, lookup-`NOT_FOUND` same-ID resend, and malformed provider response produce the documented `201`, `202`, or replayable `502` outcomes without false financial effects.
- **AC-M6-004:** Provider success lost before local persistence recovers by lookup; a crash after ledger/outbox commit resumes finalization without another journal or outbox event.
- **AC-M6-005:** Kafka unavailability cannot roll back completed business state. Duplicate publication/delivery and semantic re-enveloping still create one notification effect.
- **AC-M6-006:** Owner-filtered reads, strict JWT authorization, bounded input, safe failure codes, and payment-reference redaction remain enforced.
- **AC-M6-007:** V008 adds final states and deferred cross-table finalization constraints without modifying merged migrations; PostgreSQL rejects a completed order lacking the captured payment, journal, or outbox evidence.

- **AC-M7A-001:** One fixed-ID integration trace proves connected HTTP server, provider client, PostgreSQL workflow, ledger, outbox append/publish, Kafka producer/consumer, and notification spans with persisted W3C context and accurate parentage.
- **AC-M7A-002:** Every response returns a validated correlation ID; ECS JSON logs carry safe trace/span/correlation context and stable codes, while seeded tokens, cookies, keys, customer markers, bodies, and payment references are absent from responses, logs, spans, metrics, and event headers.
- **AC-M7A-003:** Prometheus scrapes only the isolated management listener. Five provisioned Grafana dashboards load without UI edits and link Prometheus exemplars to Tempo and Tempo traces to Loki logs.
- **AC-M7A-004:** Orders, payment states/outcomes and resilience, ledger, outbox, Kafka/DLT/lag, notification integrity, readiness/drain, JVM, executor, and HikariCP signals use a documented and code-enforced bounded label policy.
- **AC-M7A-005:** Every version-controlled alert uses a real exported metric, bounded time window/threshold, severity/service labels, and a matching runbook. Provisional SLOs explicitly separate declines from failures and local evidence from production guarantees.
- **AC-M7A-006:** In-memory, HTTP/Kafka propagation, redaction, cardinality, configuration, and failing-exporter tests pass; telemetry backend failure leaves the completed order, balanced journal, and durable outbox unchanged.

## Full-flow acceptance criteria

Milestones 6 and 7A deliver AC-001 through AC-011, AC-013, AC-014, and AC-016. Operator AC-012 remains Milestone 7B, and AC-015 is rerun for every milestone.

- **AC-001:** A valid success request returns `201`, a `COMPLETED` order, a `CAPTURED` payment, two balanced ledger entries, and one pending or published outbox event.
- **AC-002:** Replaying AC-001 with the same key and semantically identical payload returns the original status and body with `Idempotency-Replayed: true`; row counts and provider-call counts do not increase.
- **AC-003:** Reusing the key with any changed business field returns `409 idempotency_key_reused` and creates no additional business rows.
- **AC-004:** Two concurrent identical requests result in exactly one order, one payment workflow, and one eventual cached result.
- **AC-005:** The authorization-decline scenario reaches terminal payment `DECLINED` and order `PAYMENT_DECLINED`, with no capture, ledger, or outbox effect.
- **AC-006:** Latency succeeds within the configured provider timeout; temporary failure retries once; unresolved timeout or exhausted temporary failure returns `202 PAYMENT_RETRY_PENDING` and creates an inspectable operation.
- **AC-007:** Every invalid payment or order transition is rejected by unit tests, and stale concurrent updates are rejected by optimistic locking.
- **AC-008:** Direct attempts to commit unbalanced, incomplete, non-positive, or mixed-currency ledger rows fail at the database boundary.
- **AC-009:** Fault injection at every capture-accounting statement proves payment `CAPTURE_ACCOUNTED`, ledger entries, and outbox event are all committed or all rolled back. Later order/payment finalization must not recreate those effects.
- **AC-010:** A publisher crash after Kafka acknowledgement but before marking the outbox row can produce a duplicate event, and event-ID plus versioned semantic-effect idempotency still creates one notification.
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
