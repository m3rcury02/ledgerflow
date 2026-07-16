# Portfolio Extension Execution Plan

- Status: Extension 5 In Progress
- Last updated: 2026-07-16
- Related plan: `docs/plans/mvp-execplan.md` (Completed)

## Purpose
This plan outlines the sequential execution of the 7 extensions required to finalize the LedgerFlow portfolio edition. Only one extension is in progress at a time, with commits and pushes occurring strictly after all gates for the active extension have passed.

## Milestones

### Extension 1 — CI/CD and software supply chain (`Complete`)
- **Status:** Complete
- **Implementation:** GitHub Actions CI, static analysis, unit/integration/architecture tests, OpenAPI/doc checks, migration validation, CodeQL, secret scanning, Trivy scanning, SBOM generation, OCI image builds, non-root runtime, action pinning, least-privilege workflow permissions, and branch protection docs.
- **Commit:** `ci: add secure build and release pipeline`

### Extension 2 — Performance and failure experiments (`Complete`)
- **Status:** Complete
- **Implementation:** k6 scenarios for traffic bursting, idempotency contention, unique-key resource growth, slow/timed-out providers, database lock contention, Kafka outage/recovery, duplicates, worker restarts, and outbox drainage.

### Extension 3 — Production-oriented containers (`Complete`)
- **Status:** Complete
- **Implementation:** Multi-stage images, Java 25 runtime, non-root execution, bounded writable storage, graceful shutdown, OCI metadata, reproducible build guidance, SBOM, vulnerability scanning, JVM resource settings.

### Extension 4 — Local Kubernetes and Helm (`Complete`)
- **Status:** Complete
- **Implementation:** kind cluster, Helm chart, API/worker Deployments, Services, probes, ConfigMaps, resource limits, security contexts, dropped capabilities, NetworkPolicies, ServiceAccounts, Helm linting, and a smoke test. No service mesh.

### Extension 5 — AWS Terraform design (`In Progress`)
- **Status:** In Progress
- **Implementation:** Validated AWS Terraform design for a two-AZ VPC, load balancer, ECS Fargate, RDS PostgreSQL, ElastiCache (if justified), Secrets Manager, CloudWatch/OTLP, IAM, and encryption. Validated with `terraform validate`, `tflint`, and `Checkov`. No actual `apply`.

### Extension 6 — Optional AI operations assistant (`Proposed`)
- **Status:** Proposed
- **Implementation:** Python FastAPI service providing AI incident assistance, deterministic fake provider default, runbook retrieval, structured output, prompt-injection resistant, and strict token/timeout bounds.

### Extension 7 — Final portfolio release (`Proposed`)
- **Status:** Proposed
- **Implementation:** Polished README, architecture diagrams, demo video script, interview discussion guide, documented trade-offs, CI/performance/Kubernetes/Terraform evidence, and final security gates. Draft PR creation.
