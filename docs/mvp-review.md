# LedgerFlow MVP Whole-Repository Review

- Review date: 2026-07-17
- Review mode: separate read-only pass over the release diff and delivered repository
- Scope: local portfolio MVP; production gaps are recorded, not disguised as delivered behavior

## Findings resolved in Milestone 8

| Finding | Severity | Resolution |
| --- | --- | --- |
| Capture-finalization rollback evidence covered the outbox insert but did not name every mutation stage. | Medium, financial consistency | Replaced it with a parameterized PostgreSQL trigger proof over journal header, journal entries, payment transition, and outbox insert; every failure leaves payment `CAPTURE_CONFIRMED` and all finalization tables empty. |
| Consumer idempotency had duplicate-delivery coverage but no explicit failure point after database commit and before Kafka offset completion. | Medium, duplicate side effect | Added the local/test-only `NOTIFICATION_OFFSET_COMMIT` checkpoint and Kafka integration proof; redelivery creates one inbox and notification effect. |
| DLT acknowledgement behavior lacked an explicit DLT publication failure proof. | Medium, blocked partition / lost poison evidence | Added the local/test-only `NOTIFICATION_DLT_PUBLISH` checkpoint; repeated failed recovery retains the source record and successful recovery creates durable terminal evidence. |
| Release documentation still described operator recovery and migration V009 inconsistently in historical sections. | Low, unsupported documentation | Added canonical evidence/inventory indexes and corrected current-state/acceptance language while retaining historical milestone context. |

No Critical or High defect was found in the delivered local MVP scope. No test, scanner,
authorization rule, constraint, or audit control was weakened. Production-gate risks remain visible
in `security/mvp-residual-risk-register.md`.

## Review coverage

| Area | Evidence reviewed | Assessment |
| --- | --- | --- |
| Financial correctness | Money types, payment capture, journal construction, V003 deferred constraints, parameterized rollback test | Positive integer INR minor units; one balanced debit/credit journal per capture; database backstops remain active. |
| State machines | Order/payment transition tests, guarded SQL, optimistic versions | Illegal/stale transitions fail without a silent state change. |
| Transaction boundaries | Workflow, provider adapter, ledger posting, outbox appender | No PostgreSQL transaction spans provider HTTP or Kafka I/O; capture accounting and outbox share one local transaction. |
| Provider unknown outcomes | Stable IDs, attempts, timeout lookup, `NOT_FOUND` resend, operator handler | Lookup precedes resend; only same-ID resend after `NOT_FOUND`; confirmed decline is not retried. |
| HTTP idempotency and concurrency | Hashed scope/key/fingerprint, database uniqueness, replay/concurrency tests | Same request replays; changed input conflicts; contenders converge on one workflow. |
| Ledger balance and immutability | V003 functions/triggers and ledger tests | Posted rows reject update/delete; corrections append; incomplete/unbalanced/mixed rows fail at commit. |
| Outbox/inbox guarantees | V004/V005, publisher lease/ack hooks, Kafka integration | Business/outbox atomic; publication/consumption at-least-once; duplicate transport event is one side effect. |
| Notification semantic identity | V006 and semantic duplicate/conflict/concurrency tests | New event ID cannot duplicate the same versioned capture effect; conflicting content fails closed. |
| DLT progress | Main retry handler, broker-ack failure proof, terminal coordinate catalog, evidence failure/recovery | Poison records do not silently commit; malformed DLT evidence is durable and later records progress. |
| Authentication/authorization | Security configuration, OpenAPI, JWT/BOLA/operator negative tests | Exact issuer/audience/time/scope/role and owner filter; customer/operator boundaries remain separate. |
| Operator recovery | Command idempotency, lease owner/token/version, cooldown/caps, handlers, immutable audit | One active logical retry; safe takeover; stale completion rejected; no general database mutation route. |
| Sensitive data | DTOs, provider reference clearing, logging/trace/DLT projection, seeded-marker tests | No PAN/CVV/real credentials; secrets and raw poison/provider payloads are absent from public/telemetry projections. |
| Metrics cardinality | meter filter, documented allowlist, dashboard/rule queries | Resource IDs, subjects, keys, URLs, event IDs, and exception text are not metric labels. |
| Tracing accuracy | HTTP/provider/JDBC scoped spans, stored traceparent, Kafka producer/consumer, operator links | Causal work retains parentage; manual recovery uses links rather than false parentage. |
| Migration safety | V001–V009 history, compatibility tests, Git diff | No existing migration changed; Milestone 8 needs no migration. |
| Test realism | PostgreSQL/Kafka/Toxiproxy Testcontainers and real mock HTTP server | Real local protocols and concurrency are exercised; no H2. Not a scale/topology proof. |
| Documentation accuracy | README, OpenAPI, ADRs, architecture, evidence and limitations | Current behavior separated from historical milestones and future production work. |
| Security scans | Pinned Trivy process and exact expiring local image policy | Findings remain visible; no application/secret exception path. Final results are recorded in `docs/mvp-evidence.md` and the risk registers. |
| Local dependency health | Compose validation, health checks, dev scripts | Nine services are loopback-bound and health-gated; single-host limits are explicit. |

## Deliberate non-findings

- Duplicate Kafka records are expected, not a defect; duplicate database side effects are the
  prohibited outcome.
- Telemetry loss during an exporter outage is accepted; changing a financial outcome because of
  telemetry is prohibited and tested.
- A `201 COMPLETED` response does not mean Kafka or notification completion; it means local
  financial finalization and durable outbox persistence.
- Quota/retention and production platform controls are known production gates, not falsely closed
  by this portfolio release.
