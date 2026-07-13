# ADR 0004: Isolate the Payment Provider and Persist Explicit Payment States

- Status: Proposed
- Date: 2026-07-11
- Decision owners: LedgerFlow maintainers

## Context

Authorization and capture cross an unreliable external HTTP boundary. Latency, temporary failures, declines, timeouts, and process crashes can leave the local application uncertain whether a provider operation occurred. Blind retries can double-authorize or double-capture, while long database transactions across HTTP harm availability.

## Decision

The payment domain owns a provider port with authorize, capture, and operation-lookup capabilities. An HTTP adapter implements it. A separate local/test mock-provider support service exercises the real HTTP boundary; it is not included in the production LedgerFlow deployable.

Each logical authorization and capture receives a distinct stable application-generated UUID operation key before the call. Every retry and lookup for that logical operation reuses the same key and equivalent money/payment identity. Authorization includes the restricted mock payment-method reference; capture uses the validated provider authorization ID and does not need that reference. The provider contract is idempotent on each key and rejects a changed payload.

No database transaction remains open during provider I/O. Before a call, a short guarded transaction persists the expected state and attempt. A later transaction records the classified result.

Persisted states distinguish:

- active authorization/capture;
- successful authorization/capture;
- terminal authorization decline;
- known temporary failure pending retry;
- unknown timeout outcome requiring reconciliation; and
- non-retryable invalid provider response.

Capture is permitted only after authorization. All transitions use an expected source state plus optimistic version. Invalid or stale transitions make no change.

Declines are terminal and never retried. A temporary failure receives at most one automatic retry. An unknown outcome is looked up before resend. Exhausted temporary or unknown outcomes become operator-visible and retryable; the operator command still reuses the original provider operation key.

If capture succeeds at the provider but local finalization fails, recovery looks up the capture key and reruns only the idempotent local finalization transaction.

The fake payment-method reference is stored only through authorization recovery and cleared after authorization succeeds or becomes terminal. The mock provider offers deterministic opaque tokens for success, bounded latency, timeout-after-processing, authorization decline, and temporary failure. It accepts no card data. Mock code is a separate fixture enabled only for local/test/demo; production rejects it and requires a separately approved real provider.

A malformed, contradictory, or amount/currency-mismatched provider response is non-retryable: order/payment become `FAILED`, the original POST snapshots a `502 provider_protocol_error` with the order location, and no ledger/outbox effect is created.

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
