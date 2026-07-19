# Trade-offs and Rejected Alternatives

This is a curated index, not a duplicate archive. The full rationale for every decision below
already lives in a primary source — an ADR or a plan's Decision log — and this document exists
only to give a technical interviewer a fast, topic-organized way into that material instead of
scrolling two multi-hundred-line execution plans. When a decision changes, the primary source is
what gets edited; this index is updated to keep pointing at it, not treated as its own record.

## MVP architecture (13 ADRs)

`docs/adr/README.md` is itself a curated trade-off index — every accepted architectural decision
for the core application, one row each, in decision order. Notable ones for an interview
conversation:

- **Modular monolith over microservices from day one**
  ([ADR 0002](adr/0002-mvp-module-boundaries-and-orchestration.md)) — feature modules with narrow
  APIs and one deployable artifact, so module boundaries are enforced (Spring Modulith, ArchUnit)
  without paying distributed-systems cost before evidence justifies it.
- **Transactional outbox, not a distributed transaction, across the ledger/Kafka boundary**
  ([ADR 0006](adr/0006-transactional-outbox-and-at-least-once-kafka.md)) — an atomic local write
  plus a leased publisher gives real at-least-once delivery with idempotent consumption, not a
  two-phase-commit dependency on Kafka.
- **No transaction held open over an HTTP call to the payment provider**
  ([ADR 0004](adr/0004-payment-provider-boundary-and-state-machine.md)) — stable operation IDs and
  lookup-first reconciliation instead, so a slow or failed provider call can never hold a database
  lock.
- **Immutable, database-enforced double-entry ledger**
  ([ADR 0005](adr/0005-immutable-balanced-double-entry-ledger.md)) — corrections are append-only;
  a trigger, not application discipline alone, rejects an unbalanced or mutated journal.
- **The public order workflow's finalization step was redesigned mid-MVP**
  ([ADR 0013](adr/0013-finalize-the-public-order-workflow-with-recoverable-local-transactions.md),
  superseding part of [ADR 0003](adr/0003-idempotent-http-write-contracts.md)) — the original
  single-short-transaction sequencing didn't survive contact with the provider-backed workflow;
  recorded as a superseding ADR, not a silent rewrite of the original.

## Portfolio extensions (Milestones 1-6)

Full rationale for every entry below is in
[`docs/plans/portfolio-extension-execplan.md`](plans/portfolio-extension-execplan.md)'s Decision
log, dated per milestone. Highlights:

- **VPC interface/gateway endpoints instead of a NAT Gateway** (Milestone 5) — cost-comparable to
  one NAT Gateway, cheaper than the two-NAT topology real AZ-independence would otherwise need,
  and categorically removes the private subnets' internet egress path rather than merely scoping
  it.
- **Kafka and an identity provider are explicitly out of scope for the Terraform design**
  (Milestone 5), unlike Milestone 4's in-cluster Kafka + Keycloak — the extension prompt's own
  service list for that milestone didn't include either; modeled as placeholder variables with
  explicit comments, not fabricated infrastructure.
- **Deterministic fake provider as the AI assistant's default, real provider opt-in only**
  (Milestone 6) — no test, script, container image, or default configuration can trigger real API
  billing or send data to a third party without a deliberate maintainer choice.
- **Retrieval happens in the service layer before any model is called, and citations are grounded
  against what was actually retrieved** (Milestone 6) — makes "never invent a runbook citation" a
  structurally enforced, tested property instead of a prompt-only request a model could ignore.
- **Fix real Checkov/Trivy findings where proportionate, accept narrowly-scoped documented
  suppressions only for genuine trade-offs** (Milestone 5) — CloudWatch retention, RDS Performance
  Insights, and a locked-down default security group were real fixes, not scanner-appeasement;
  the ALB's public ingress and the RDS-managed-password-rotation check are accepted trade-offs
  with a recorded reason each.
- **A one-line, narrowly-scoped directory skip for `deploy/kind/dependencies/` (Milestone 4), and
  the deliberate decision *not* to do the same for Milestone 5's Terraform** — the Kubernetes
  manifests were vendored, unmodifiable upstream files; the Terraform is hand-authored, so the
  target there was passing the scanner cleanly, not skip-listing a directory. Two milestones,
  same scanner finding shape, different resolution, because the underlying facts differed.

## Rejected wholesale: reusing the archived extension attempt

[Decision log, 2026-07-18](plans/portfolio-extension-execplan.md#decision-log) — the seven
portfolio extensions were rebuilt from scratch rather than cherry-picked from
`archive/main-before-d2f1721-2026-07-17`, a prior attempt that was merged then reverted. The
archive has specific, identifiable defects (binaries committed, some fabricated evidence); per
maintainer instruction, it is not reused or read from anywhere in this plan.

## What this document deliberately doesn't do

It doesn't re-explain *why* in enough depth to stand alone — that would duplicate the ADRs and
Decision logs and drift out of sync with them over time. Follow a link above for the full
argument, alternatives actually considered, and the evidence behind each choice.
