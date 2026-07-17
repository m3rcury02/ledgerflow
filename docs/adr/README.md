# LedgerFlow Architecture Decision Index

Accepted records are historical. A later decision may supersede only a stated part of an earlier
record; the original remains in Git. There are no fully superseded ADRs at the MVP release.

| ADR | Status | Decision |
| --- | --- | --- |
| [0001](0001-record-architecture-decisions.md) | Accepted | Record significant architectural decisions as immutable Markdown history. |
| [0002](0002-mvp-module-boundaries-and-orchestration.md) | Accepted | Feature modules, narrow APIs, orders orchestration, payment hexagon, and explicit JDBC. |
| [0003](0003-idempotent-http-write-contracts.md) | Accepted; workflow sequencing partially superseded by 0013 | Durable scoped key/hash concurrency and exact replay. ADR 0013 replaces the original one-short-transaction sequencing for the provider-backed workflow. |
| [0004](0004-payment-provider-boundary-and-state-machine.md) | Accepted | Stable operation IDs, lookup-first unknown reconciliation, explicit state machine, and no transaction over HTTP. |
| [0005](0005-immutable-balanced-double-entry-ledger.md) | Accepted | Immutable balanced journals, database enforcement, and append-only corrections. |
| [0006](0006-transactional-outbox-and-at-least-once-kafka.md) | Accepted | Atomic business/outbox writes, leased publisher, inbox, DLT, and accurate at-least-once guarantees. |
| [0007](0007-correlation-and-opentelemetry-propagation.md) | Accepted | Safe correlation plus W3C context across HTTP, durable outbox, Kafka, and recovery links. |
| [0008](0008-secured-operator-recovery.md) | Accepted | Sanitized inspection, separate permissions, idempotent leased retries, and immutable audit/break-glass evidence. |
| [0009](0009-runtime-resilience-boundaries.md) | Accepted | Explicit deadlines, retry classification, circuit breaker, bulkhead, backpressure, and graceful drain. |
| [0010](0010-isolate-management-endpoints.md) | Accepted | Separate management listener and bounded/coalesced dependency probes. |
| [0011](0011-use-versioned-notification-semantic-identity.md) | Accepted | Separate transport and semantic notification identities with conflict detection. |
| [0012](0012-catalog-terminal-dlt-input-by-actual-coordinate.md) | Accepted | Durable terminal malformed-DLT evidence before offset progress. |
| [0013](0013-finalize-the-public-order-workflow-with-recoverable-local-transactions.md) | Accepted | Recoverable public workflow and truthful local finalization without distributed transactions. |

Proposals are not implementation authority. At release, the index contains no Proposed,
Deprecated, or fully Superseded record. When adding one, follow ADR 0001 and update this index.
