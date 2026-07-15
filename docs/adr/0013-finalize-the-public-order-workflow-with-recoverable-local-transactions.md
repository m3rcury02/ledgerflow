# ADR 0013: Finalize the Public Order Workflow with Recoverable Local Transactions

- Status: Accepted
- Date: 2026-07-15
- Decision owners: LedgerFlow maintainers

## Context

The existing modules separately provide idempotent order creation, a provider-facing payment state machine with stable operation IDs, immutable balanced ledger posting, a transactional outbox, and idempotent Kafka notification handling. Milestone 6 must expose those capabilities as one truthful public workflow without holding PostgreSQL transactions across provider or Kafka I/O and without claiming a distributed or end-to-end exactly-once transaction.

A process may stop after the provider succeeds, after capture accounting commits, or after Kafka acknowledges an event. Concurrent retries may enter the same workflow. Every boundary therefore needs a durable identity, a guarded transition, and a replay result that can be derived from PostgreSQL and provider lookup.

## Decision

The `orders` module owns the concrete public workflow coordinator. It depends only on narrow `payments.api` and `ledger.api` use cases; no generic workflow framework or cross-module internal dependency is introduced.

The workflow uses these boundaries:

1. A short PostgreSQL transaction reserves the customer/idempotency-key pair with a canonical request hash, creates a `PAYMENT_PROCESSING` order, creates one payment with its stable authorization operation ID, and attaches the order resource to the idempotency record. The capture operation ID is generated once and persisted by the guarded `CAPTURING` transition before capture I/O.
2. Authorization, capture, and provider lookup occur outside PostgreSQL transactions. Unknown outcomes are looked up by the existing operation ID. Only a confirmed `NOT_FOUND` permits an equivalent resend with that same ID.
3. The existing ledger application service uses one local transaction to lock the payment, post exactly one balanced immutable capture journal, transition to `CAPTURE_ACCOUNTED`, and append exactly one logical payment-captured outbox event.
4. A final short PostgreSQL transaction locks the idempotency record, verifies the persisted payment/journal/outbox relationship, transitions `CAPTURE_ACCOUNTED -> CAPTURED`, transitions the order to `COMPLETED`, and stores the public idempotency result. Deferred database constraints reject a terminal state that lacks its matching financial evidence.
5. The outbox publisher and notification consumer remain asynchronous and at-least-once. HTTP success does not wait for Kafka or notification delivery. Transport event-ID idempotency and versioned semantic-effect idempotency remain separate controls.

Recent active provider states temporarily suppress competing recovery. After a bounded active-operation deadline, a retry reconciles by lookup. Optimistic state/version guards, row locking at local transaction boundaries, and database uniqueness make concurrent resumptions converge without duplicating provider operations, journal posting, logical outbox events, or notification effects.

## Consequences

- `POST /api/v1/orders` can return a truthful `201` completed/declined result, `202` recoverable result, or sanitized `502` provider-protocol failure. Notification delivery is never represented as synchronous.
- A crash after provider success resumes through lookup with the original operation ID. A crash after accounting resumes by verifying and finalizing existing local effects.
- Kafka unavailability does not roll back a completed financial transaction; the durable outbox records the delivery obligation.
- The public request now includes the opaque demonstration payment-method reference in canonical idempotency hashing. Historical pre-Milestone-6 order rows remain readable with a nullable payment summary, but they cannot be upgraded into this workflow implicitly.
- Finalization spans multiple recoverable local transactions. Clients and operators must not infer atomicity across provider, PostgreSQL, Kafka, and notification systems.
- The deterministic mock-provider reference is for local/test demonstration only. A production provider and secured operator recovery require separate approved decisions.

## Alternatives considered

- Holding a database transaction open across provider calls was rejected because latency and failure would retain locks and connections while still not making the provider atomic with PostgreSQL.
- Waiting for Kafka publication or notification before HTTP success was rejected because it would couple the business commit to asynchronous infrastructure and misstate delivery guarantees.
- Creating new provider operation IDs during recovery was rejected because it could duplicate authorization or capture.
- A generic saga/workflow engine was rejected as unnecessary complexity for the single currently approved orchestration.
- Combining ledger/outbox accounting and public finalization into a new broad transaction was rejected because the existing accounting boundary already has tested replay semantics; the narrow finalization constraint makes the intervening crash state explicitly recoverable.

## Validation

Contract, unit, PostgreSQL, HTTP authorization/idempotency, provider timeout/lookup, crash recovery, concurrency, Kafka availability/publication, duplicate transport, semantic duplicate, architecture, security, migration, and full verification tests enforce this decision. Delivery documentation must continue to describe at-least-once Kafka behavior rather than end-to-end exactly-once delivery.
