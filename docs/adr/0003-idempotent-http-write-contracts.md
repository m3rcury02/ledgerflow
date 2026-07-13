# ADR 0003: Define Durable Idempotent HTTP Write Contracts

- Status: Accepted for Create Order
- Date: 2026-07-13
- Decision owners: LedgerFlow maintainers

## Context

Clients retry writes after timeouts, lost responses, or process failures. Create Order must not
create a second order under sequential or concurrent retry, and a key must not silently identify two
different requests. Redis or an in-memory cache cannot commit atomically with PostgreSQL business
data. The current slice performs no external work, so a renewable workflow lease would create
recovery states without solving a present problem.

## Decision

`POST /api/v1/orders` requires `Idempotency-Key`. LedgerFlow authenticates the caller and validates
the request before it:

1. hashes the validated key bytes with SHA-256;
2. creates a SHA-256 fingerprint from a versioned, length-delimited canonical encoding of the typed
   business fields;
3. attempts to insert an `IN_PROGRESS` record scoped by authenticated subject, stable operation, and
   key hash;
4. creates a PostgreSQL-generated UUIDv7 `CREATED` order if it owns the claim; and
5. stores the `201` resource ID, body, and `Location` and marks the claim `COMPLETED`.

Steps 3–5 execute in one short PostgreSQL transaction. The idempotency composite primary key is the
concurrency control. `INSERT ... ON CONFLICT DO NOTHING` waits for a conflicting uncommitted row.
After the winner commits, the contender compares the committed fingerprint. If the winner rolls
back, the contender can insert and become the owner.

The same scope, key, and fingerprint returns the immutable original status, body, resource ID, and
`Location` with `Idempotency-Replayed: true`. The replaying HTTP operation gets its own correlation
and trace context. A different fingerprint returns `409 idempotency_key_reused`.

Raw keys and raw requests are never stored or logged. Correlation, tracing, authorization, and JSON
presentation details are excluded from the fingerprint. Idempotency rows do not expire in the MVP.

Later operations that span provider I/O or background recovery must make a separate accepted
decision about leases, takeover, and durable workflow state. This ADR does not authorize payment or
operator-retry behavior.

## Consequences

### Positive

- Order creation and its replay record cannot commit separately.
- PostgreSQL gives deterministic concurrent-key behavior without another production dependency.
- Changed-payload key reuse has a stable conflict response.
- There is no committed `IN_PROGRESS` state to recover in the current slice.

### Costs and risks

- A contender waits on PostgreSQL's unique-index conflict until the winner resolves or a configured
  database timeout fires.
- Response snapshots and key records consume durable storage.
- The canonical encoding must remain stable for the lifetime of API v1.
- Later external workflows cannot hold this transaction open and need a separate recovery design.

## Alternatives considered

### Application-level pre-check followed by insert

Rejected because a check-then-act race can create two orders unless the database is still made the
authority.

### Cache idempotency in memory or Redis

Rejected because it cannot commit atomically with the order and adds a consistency boundary.

### Commit an in-progress lease before creating the order

Deferred. It is useful only when work must survive beyond the short local transaction, such as a
future provider workflow.

### Return the latest resource on replay

Rejected because it makes the result of the original command change over time.
