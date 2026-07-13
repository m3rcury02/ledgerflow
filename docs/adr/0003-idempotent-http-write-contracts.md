# ADR 0003: Define Durable Idempotent HTTP Write Contracts

- Status: Proposed
- Date: 2026-07-11
- Decision owners: LedgerFlow maintainers

## Context

Clients and operators will retry HTTP writes after timeouts, lost responses, or process failures. A retry must not create a second order, repeat a provider workflow, or schedule duplicate recovery. A key alone is insufficient because concurrent requests, changed payloads, and crashes while work is in progress need explicit behavior.

## Decision

`POST /api/v1/orders` and operator retry requests require `Idempotency-Key`.

For each operation, LedgerFlow:

1. authenticates and validates the request;
2. normalizes the typed business fields and computes a SHA-256 request fingerprint;
3. hashes the raw key with SHA-256;
4. claims a unique `{principal, OpenAPI operation plus concrete target ID where present, key hash}` row with an owner token and renewable lease;
5. creates or resumes the same resource/workflow; and
6. stores the accepted HTTP status, body, resource ID, and replayable headers before returning.

Raw keys and request bodies are not stored or logged. Correlation, trace, authorization, and other transport headers are excluded from the request fingerprint.

The same scope, key, and fingerprint returns the stored status/body/`Location` without repeating business effects. The replaying operation receives its own correlation and trace context plus `Idempotency-Replayed: true`.

The same scope and key with a different fingerprint returns `409 idempotency_key_reused`. A concurrent matching request may wait briefly for completion; if the original remains in progress, it returns `409 idempotency_request_in_progress` and `Retry-After` without starting work.

The lease exceeds or is renewed during bounded provider work. Renew, takeover, response completion, and failure updates compare owner token and version. If a lease expires, a new owner resumes the existing resource from durable state rather than creating a new one; the stale owner cannot perform an external call or overwrite the response after takeover.

Operator retry requests store their immutable accepted `202` snapshot with the retry row and apply the same fingerprint/mismatch/replay rules.

The create-order snapshot is immutable. If an operator later advances a `202` order, a POST replay still returns the original `202`; `GET /api/v1/orders/{id}` returns current state.

MVP idempotency rows do not expire automatically. Retention changes require a contract and data-retention decision.

## Consequences

### Positive

- Lost HTTP responses and retries cannot duplicate financial effects.
- Different-payload key reuse has deterministic behavior.
- In-progress crashes can be recovered without guessing whether a resource exists.
- Current resource state and original command result have distinct, documented meanings.

### Costs and risks

- Response snapshots and idempotency rows consume durable storage.
- Request normalization must remain stable for the lifetime of an API version.
- Lease expiry and recovery add concurrency tests and operational states.
- A caller must use GET after a `202` to see later progress.

## Alternatives considered

### Trust client-generated order IDs only

Rejected because it does not define changed payloads, stored responses, concurrent processing, or operator-command retries.

### Cache idempotency in memory or Redis

Rejected because PostgreSQL already participates in resource creation and provides the required transactional durability. An extra datastore would add failure modes.

### Return the latest resource on POST replay

Rejected because it changes the result of the original command and makes retry behavior time-dependent.

### Expire keys after a short fixed period

Deferred until retention and duplicate-order risk are evaluated with real usage requirements.
