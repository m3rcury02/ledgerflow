# LedgerFlow MVP Residual-Risk Register

- Review date: 2026-07-17
- Owner: LedgerFlow maintainer unless reassigned
- Scope: local portfolio MVP

No item below is accepted for a production deployment. “Accepted locally” means the loopback-only
demonstration may proceed with the recorded mitigation; the item remains a production gate.

| ID | Severity | Residual risk | Existing mitigation and evidence | MVP decision | Production/re-review trigger |
| --- | --- | --- | --- | --- | --- |
| LF-MVP-R001 | High | Unbounded successful orders, idempotency records, attempts, and immutable audit data can exhaust storage; early idempotency deletion could repeat a write. | Per-instance write rate limits, bounded request bodies, growth metrics, durable unique keys; no purge job. | Accepted locally; production gate. | Define client retry horizon, customer/storage quota, archive/retention and purge proof before non-local deployment. |
| LF-MVP-R002 | High | Single-node PostgreSQL/Kafka can lose local data or become unavailable. | Testcontainers and Compose health/failure proofs; durable local transactional design. | Accepted locally; production gate. | Select managed/replicated topology and prove backup, restore, failover, RPO/RTO. |
| LF-MVP-R003 | High | Deterministic mock provider does not prove a real provider's idempotency, lookup semantics, credentials, or webhook behavior. | Stable operation IDs, lookup-first unknown recovery, bounded retry, contract fixture, no real credentials. | Accepted for portfolio only. | New provider ADR, threat review, sandbox certification, reconciliation and credential controls. |
| LF-MVP-R004 | High | Local identity and network setup does not implement production MFA, key/secret rotation, network policy, mTLS, Kafka ACLs, or database least privilege. | Exact JWT issuer/audience/time/role/scope checks, BOLA tests, loopback ports, documented ACL matrix. | Accepted locally; production gate. | Platform design and independent authorization/network validation before exposure. |
| LF-MVP-R005 | Medium | Per-instance rate limiting and circuit/bulkhead state do not provide cluster-wide abuse or capacity control. | Bounded local maps, payload limits, concurrency bulkhead, explicit ingress requirement. | Accepted locally. | Distributed topology, aggregate edge limits, load/abuse testing, and capacity targets. |
| LF-MVP-R006 | Medium | Telemetry can be lost during backend failure and local retention/cost is not sized. | Failure-isolated exporters, redaction/cardinality tests, bounded batches, sustained-export alert. | Accepted locally. | Production telemetry capacity, retention, access control, sampling, and outage runbook validation. |
| LF-MVP-R007 | Medium | Operator retry is powerful and a compromised operator/admin token can trigger bounded recovery. | Separate read/retry/break-glass permissions, JWT-derived identity, mandatory reason, cooldown/caps, one-use approval, immutable audit, safe handlers. | Accepted locally. | MFA/JIT access, approval workflow integration, alert ownership, periodic access review. |
| LF-MVP-R008 | Medium | The MVP has no formal performance, soak, multi-region, disaster, or chaos qualification. | Deterministic concurrency and failure-path integration tests only. | Accepted locally; claims explicitly limited. | Workload model, SLO calibration, load/soak/failover tests in intended topology. |
| LF-MVP-R009 | Medium | Current official local Compose images contain exact scanner findings awaiting compatible patches. | Findings remain visible; exact digest/package/CVE/expiry policy; loopback/resource controls. | Temporarily accepted only as recorded. | Follow `local-development-container-risk-register.md`; expiry is 2026-08-13 or fixed image availability, whichever is earlier. |
| LF-MVP-R010 | Low | OpenTelemetry Logback instrumentation is an alpha artifact and may change behavior on upgrade. | Version pinned by BOM, seeded redaction tests, exporter failure isolation, no business dependency on telemetry. | Accepted locally. | Re-review on any OTel/Boot/logging upgrade or before production support commitment. |

## Related exact vulnerability records

Container CVE identifiers, installed/fixed versions, reachability, official-image availability,
owners, acceptance/expiry dates, and exact scanner tuples are intentionally not duplicated here.
They remain authoritative in `local-development-container-risk-register.md` and
`config/security/local-compose-vulnerability-exceptions.json`. Repository-secret and packaged
application findings have no exception path.

## Review rules

- New Critical or High findings block release unless remediated or processed through the existing
  documented, scoped, owned, expiring exception process.
- A changed image digest, dependency version, architecture boundary, provider, data category,
  exposure model, or scanner result triggers review.
- Local acceptance cannot be copied to production. Production disposition requires new evidence
  and an explicit decision owner.
