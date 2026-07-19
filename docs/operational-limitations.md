# LedgerFlow MVP Operational Limitations

LedgerFlow demonstrates backend correctness and recovery patterns on a developer workstation. It
is not a deployable production reference architecture and makes no capacity, availability,
regulatory, or disaster-recovery claim.

## Functional limits

- Currency is restricted to INR, amounts are positive integer minor units, and only one full
  capture is supported. Refunds, partial capture, disputes, fees, settlement, tax, FX, and payout
  are absent.
- `PAYMENT_CLEARING` debit and `MERCHANT_PAYABLE` credit demonstrate a captured liability. The MVP
  does not claim merchant-of-record accounting or revenue recognition.
- The provider is a deterministic integration-test HTTP service. A real provider, credential
  lifecycle, webhooks, reconciliation files, and provider-specific compliance are not delivered.
- Notification persistence is a semantic side effect record; no email, SMS, or push delivery is
  attempted.
- Redis-compatible Valkey is local infrastructure only and is not an application dependency.

## Reliability limits

- PostgreSQL is the only business source of truth. The Compose database is single-node and has no
  backup, point-in-time recovery, replication, encryption-at-rest design, or restore drill.
- Kafka Compose is one combined broker/controller. No replication, rack failure, broker failover,
  retention sizing, partition scaling, or production ACL configuration is demonstrated.
- Publication and consumption are at-least-once. Unique financial identities, event ID/hash, and a
  versioned semantic effect make current database effects idempotent; this is not end-to-end
  exactly-once delivery.
- Circuit breakers, rate limits, and some caches are per process. A production edge must enforce
  aggregate unauthenticated and multi-instance limits.
- Retry, lease, and shutdown bounds are configurable and tested locally. They are not calibrated
  from production traffic or dependency latency distributions.

## Security and privacy limits

- The local Keycloak realm defines roles/scopes but intentionally contains no users, passwords, or
  client secrets. Production identity lifecycle, MFA, workload identity, key rotation, revocation,
  and emergency access require platform design.
- Compose ports bind to loopback, but container networking is not a substitute for production
  ingress, mTLS, network policy, Kafka ACLs, database role separation, or a secret manager.
- No PAN, CVV, real payment credential, or customer profile is accepted. This reduces but does not
  replace a privacy impact assessment, retention policy, and jurisdiction-specific compliance.
- Local Compose image vulnerability exceptions expire and are prohibited for production. See the
  exact risk register rather than treating a passing local scan as production acceptance.

## Data growth and lifecycle

- Idempotency rows intentionally have no deletion job because deleting them before the supported
  retry horizon can replay an old write. A production launch requires a defined maximum client
  retry window, archive/retention policy longer than that window, and safe purge proof.
- Customer order quotas and storage budgets are not implemented. Rate limiting bounds request
  attempts per instance but does not bound long-term successful order or audit-row growth.
- Ledger and privileged audit rows are immutable and must be archived, partitioned, or retained
  under an approved financial/audit policy rather than deleted casually.
- Outbox, inbox, attempt, DLT, trace, log, and metric retention/capacity are development defaults,
  not production sizing.

## Observability and deployment limits

- Local dashboards, alerts, trace links, and SLO expressions are provisioned and validated. Local
  measurements are demonstration evidence, not production objectives or error-budget history.
- Telemetry export is intentionally failure-isolated and may lose observability data during a
  backend outage; it never becomes part of a financial transaction.
- Management port isolation is an application listener boundary plus deployment requirement. A
  production ingress/network policy must prove it is unreachable from public and customer paths.
- A local `kind` Kubernetes/Helm deployment and a validated, never-applied AWS Terraform design
  were added after this document was first written (see
  [`docs/kubernetes-deployment.md`](kubernetes-deployment.md) and
  [`docs/aws-terraform-design.md`](aws-terraform-design.md)). Neither involves a real cloud
  account, a paid service, a production credential, or a production environment — the Terraform
  design is explicitly never applied.

## Release interpretation

The `v1.0.0-mvp` tag identifies a reproducible portfolio demonstration. Promotion beyond local
development requires closing every production-gate item in
`docs/security/mvp-residual-risk-register.md`, re-running scans without copying local exceptions,
load and failure testing in the intended topology, backup/restore evidence, and an operational
readiness review.
