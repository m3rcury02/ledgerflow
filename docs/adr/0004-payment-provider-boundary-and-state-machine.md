# ADR 0004: Isolate the Payment Provider and Persist Explicit Payment States

- Status: Accepted
- Date: 2026-07-11
- Accepted: 2026-07-13
- Decision owners: LedgerFlow maintainers

## Context

Authorization and capture cross an unreliable external HTTP boundary. Latency, temporary failures, declines, timeouts, and process crashes can leave the local application uncertain whether a provider operation occurred. Blind retries can double-authorize or double-capture, while long database transactions across HTTP harm availability.

## Decision

The payment domain owns a provider port with authorize, capture, and operation-lookup capabilities. A JDK HTTP adapter implements it with explicit connection and per-request timeouts. A deterministic integration-test provider fixture exercises the real HTTP boundary; it is not included in the production LedgerFlow deployable.

Each logical authorization and capture receives a distinct stable application-generated UUID operation key before the call. Every retry and lookup for that logical operation reuses the same key and equivalent money/payment identity. Authorization includes the restricted mock payment-method reference; capture uses the validated provider authorization ID and does not need that reference. The provider contract is idempotent on each key and rejects a changed payload.

No database transaction remains open during provider I/O. Before a call, a short guarded transaction persists the expected state and attempt. A later transaction records the classified result.

Persisted states distinguish:

- active authorization/capture;
- successful authorization and provider-confirmed capture;
- terminal authorization decline;
- known temporary failure pending retry;
- unknown timeout outcome requiring reconciliation; and
- non-retryable invalid provider response.

Capture is permitted only after authorization. Provider capture success enters `CAPTURE_CONFIRMED`. The ledger-only milestone then transitions it to interim `CAPTURE_ACCOUNTED` in the same local transaction as the balanced journal. Final `CAPTURED` remains reserved for later order and outbox finalization. All transitions use an expected source state plus optimistic version or a payment-row lock. Invalid or stale transitions make no change.

The payment table copies order money so every provider call has immutable stage-local input. A payment-owned constraint trigger may read only `orders.id`, `orders.amount_minor`, and `orders.currency` during payment insert/update to reject a mismatch. This is a narrow accepted exception to the no-cross-module-SQL rule; application repositories still do not query another module's tables.

Declines are terminal and never retried. A temporary failure receives at most one automatic retry using bounded exponential backoff and jitter. An unknown outcome is looked up before resend. Exhausted temporary and unknown outcomes remain durable retry/reconciliation states; operator projection and commands are deferred to Milestone 7 and will reuse the original provider operation key.

If the process stops after provider success but before local persistence, recovery looks up the same request ID and records the confirmed result without another provider effect. A lookup result of `NOT_FOUND` is the only case that permits resending the equivalent request with the same ID.

The fake payment-method reference is stored only through authorization recovery and cleared after authorization succeeds or becomes terminal. The mock provider offers deterministic opaque tokens for success, bounded latency, timeout-after-processing, authorization/capture decline, temporary failure, and deliberately invalid response. It accepts no card data. Mock service code exists only in the integration-test fixture. No public or production workflow invokes the payment use case in this milestone; a real provider requires a separately approved decision.

A malformed or contradictory provider response is non-retryable and moves the payment to `FAILED` with a sanitized code. Public order/error mapping and amount/currency echo validation are deferred until the order and financial-finalization flow is connected. The later accepted ledger milestone adds no provider I/O or public route; it accounts only an already confirmed capture.

## Consequences

### Positive

- Provider ambiguity cannot silently become a duplicate financial operation.
- Payment rules are testable without Spring HTTP details.
- Database locks are not held across network latency.
- Crash recovery has a durable starting point and operation identifier.

### Costs and risks

- Provider success and local database finalization cannot be atomic.
- More states and reconciliation tests are required.
- The real provider selected later must support idempotent operations or equivalent lookup semantics.
- Synchronous normal processing still consumes an HTTP request thread within bounded timeouts.

## Alternatives considered

### Hold one database transaction across provider calls

Rejected because it cannot make provider and PostgreSQL atomic and would hold locks/connections during network delay.

### Retry with a new provider key

Rejected because it can duplicate authorization or capture after an ambiguous timeout.

### Treat every timeout as failure

Rejected because a timeout describes the response path, not the provider's business outcome.

### Embed the mock provider inside the production application

Rejected because it weakens the external boundary and risks exposing failure controls in production.
