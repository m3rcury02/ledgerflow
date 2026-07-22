# Résumé Bullets

Every bullet below is grounded in a real, checkable number or property from this repository —
no bullet here claims anything not directly evidenced by a linked doc, test, or command output.
Pick the subset relevant to a given role rather than using all of them; a résumé with 20 bullets
from one project is its own red flag.

## Backend / payments engineering

- Designed and implemented an idempotent, JWT-secured order-and-payment API in Java 25 / Spring
  Boot 4.1, enforcing exact-replay vs. conflict semantics on a scoped idempotency key so a
  retried write with an unchanged body returns the original result and a changed field returns
  `409` — proven with real HTTP requests, not just unit tests
  ([`docs/api-design.md`](api-design.md), ADR 0003/0013).
- Built an immutable, database-enforced double-entry ledger (a Postgres trigger rejects an
  unbalanced or mutated journal, not just application-layer validation) posting one balanced
  journal per captured payment ([ADR 0005](adr/0005-immutable-balanced-double-entry-ledger.md)).
- Implemented a transactional-outbox + leased-publisher pattern for at-least-once Kafka delivery
  with idempotent consumption, avoiding a two-phase-commit dependency between PostgreSQL and
  Kafka ([ADR 0006](adr/0006-transactional-outbox-and-at-least-once-kafka.md)).
- Designed a payment-provider integration boundary with stable operation IDs and lookup-first
  reconciliation for unknown/timeout outcomes, so no database transaction is ever held open
  across an external HTTP call
  ([ADR 0004](adr/0004-payment-provider-boundary-and-state-machine.md)).

## Reliability and resilience engineering

- Ran and recorded 11 real performance/failure experiments (not simulated) against
  Testcontainers-backed PostgreSQL/Kafka, including provider timeout, mid-transaction crash
  recovery, database-lock contention, and a telemetry-backend outage that never rolled back a
  financial transaction — full numbers in
  [`docs/performance-experiments.md`](performance-experiments.md).
- Implemented explicit resilience boundaries — deadlines, retry classification, a circuit
  breaker, a bulkhead, and graceful drain — verified with a real measured shutdown of 154.4ms
  from actual `SIGTERM` log timestamps ([`docs/container-hardening.md`](container-hardening.md),
  [ADR 0009](adr/0009-runtime-resilience-boundaries.md)).

## DevSecOps / CI / supply chain

- Built a GitHub Actions pipeline (`ci.yml`, `codeql.yml`, `security-scan.yml`) with a CycloneDX
  SBOM, Trivy image scanning, CodeQL static analysis, and Dependabot across GitHub Actions,
  Gradle, and Docker ecosystems, with every third-party Action pinned to a full commit SHA
  resolved live, not copy-pasted ([`.github/workflows/`](../.github/workflows/)).
- Built and ran a Docker-socket-privileged, Trivy-based `scripts/security-scan` gate covering
  repository secrets, packaged Java dependencies, and every Compose service image, with zero
  exception path for repository-secret or packaged-application findings — only narrowly-scoped,
  expiring, documented exceptions for local-dev-only container images
  ([`docs/security/`](security/)).

## Container and Kubernetes engineering

- Hardened a multi-stage Docker build (non-root user, read-only-root-filesystem-compatible,
  byte-for-byte reproducible `bootJar` verified via two independent builds' matching SHA-256)
  and closed 5 real HIGH-severity CVEs in the base image by removing unused package chains,
  shrinking the image from 24.0 MiB to 11.1 MiB (44→30 packages) while keeping a real end-to-end
  order working against the hardened image
  ([`docs/container-hardening.md`](container-hardening.md)).
- Deployed a two-role (API/worker) Helm chart to a real local Kubernetes cluster with
  least-privilege ServiceAccounts, deny-by-default NetworkPolicies, and a HorizontalPodAutoscaler
  that measurably scaled 2→4 replicas in 27 seconds at 89% CPU under a real load burst — not a
  synthetic or mocked metric ([`docs/kubernetes-deployment.md`](kubernetes-deployment.md)).
- Diagnosed and fixed four real, only-discoverable-by-deploying Kubernetes issues (a Kafka
  controller-quorum self-registration deadlock, under-thresholded liveness probes on three
  dependencies, a `tcpSocket` probe that could never pass on a loopback-only sidecar, and a
  Keycloak token-issuer mismatch between port-forwarded and in-cluster access) — recorded with
  root cause, not just "fixed it" ([`docs/kubernetes-deployment.md`](kubernetes-deployment.md)).

## Cloud infrastructure-as-code

- Designed a two-AZ AWS reference architecture (VPC, ECS Fargate, RDS Multi-AZ, ECR,
  CloudWatch) in Terraform with zero NAT Gateway — private subnets reach AWS services only
  through VPC endpoints, so there is no internet egress path to misconfigure — validated clean
  through `terraform validate`, TFLint, Checkov (0 failed / 20 suppressed-with-rationale), and
  an independent Trivy Terraform scanner ([`docs/aws-terraform-design.md`](aws-terraform-design.md)).
- Caught 4 real infrastructure defects invisible to 5 passing static-analysis tools (an ALB
  health-check port gap that would have hung every rollout, a Fargate-incompatible `tmpfs`
  configuration, datasource environment variables that didn't match the application's real
  configuration, and long-running tasks holding the RDS master/migration identity) through manual
  design review — then separated bootstrap, migration, API, and worker database authority and
  documented explicitly what "validated" does and doesn't prove without ever running a real
  `terraform apply`
  ([`docs/aws-terraform-design.md`](aws-terraform-design.md), "A note on what 'validated' does
  and doesn't mean here").

## AI / LLM integration

- Built a FastAPI incident-summary service with a provider-swappable architecture — a
  deterministic fake provider as the zero-cost, zero-network default and an OpenAI Responses API
  provider behind explicit opt-in — with retrieval-before-generation grounding so every citation
  traces to a real, curated 16-entry runbook corpus and a model can never invent one
  ([`docs/ai-operations-assistant.md`](ai-operations-assistant.md)).
- Proved secrets never reach a third-party LLM provider by intercepting the real outbound HTTP
  request the OpenAI SDK builds (`httpx.MockTransport`) and asserting on its actual body — not a
  documentation claim or a test against a stand-in — across 69 passing tests, `ruff`-clean
  ([`ai-assistant/tests/test_openai_provider_secrets_never_sent.py`](../ai-assistant/tests/test_openai_provider_secrets_never_sent.py)).
- Documented honestly, rather than overclaiming, the difference between structural
  prompt-injection mitigation (tested directly) and behavioral proof against a live model (not
  provable by any automated test) — including narrowing an eval-harness check that would have
  produced a false sense of coverage
  ([`docs/ai-operations-assistant.md`](ai-operations-assistant.md), "Evaluation fixtures").

## What these bullets deliberately avoid

No "reduced latency by X%" without a load-tested baseline to compare against, no "scaled to
millions of users," no "production-grade" without the residual-risk caveats attached
(`docs/residual-risks.md`), and no invented team size, timeline pressure, or business impact —
this is a solo portfolio project on a developer workstation, and it's presented as exactly that.
