# Portfolio Extension Plan

## Metadata

- Status: In Progress
- Owner: Gunal (gunal2002@gmail.com)
- Created: 2026-07-18
- Last updated: 2026-07-18
- Approved by: Gunal (gunal2002@gmail.com), via conversation on 2026-07-18
- Approval date: 2026-07-18
- Current milestone: Milestone 1 complete; Milestone 2 (performance and failure
  experiments) awaits separate approval before starting, per the checkpoint execution
  style approved on 2026-07-18.

## Purpose and outcome

The canonical MVP (`docs/plans/mvp-execplan.md`, Milestones 1–8) is complete and is not
reopened or rewritten by this plan. This plan adds seven portfolio-oriented extensions on
top of the completed MVP so the repository demonstrates production-adjacent engineering
practice (CI/CD, performance/failure testing, hardened containers, local Kubernetes,
validated-but-unapplied AWS infrastructure-as-code, an optional AI operations assistant,
and a final interview-ready release) without touching the MVP's approved behavior,
migrations, or ADRs.

When complete, a technical interviewer will be able to: watch pull requests run a real
security-gated CI pipeline; read recorded (not fabricated) performance and failure
experiment results; run the application as a hardened non-root container; deploy it to a
local `kind` cluster with one command; read a validated (never-applied) AWS Terraform
design with a cost estimate; optionally exercise a fake-provider-backed AI incident
assistant; and read a portfolio README that cites real evidence for every claim.

## Current state

Repository evidence gathered 2026-07-18:

- Build: Gradle Kotlin DSL, Java 25 toolchain, Spring Boot 4.1, single deployable module
  `application` producing one artifact `application/build/libs/ledgerflow.jar`
  (`application/build.gradle.kts:138`). There is **no separate worker artifact** — the
  outbox publisher (`modules/messaging/.../OutboxPublisher.java`) and the operator retry
  worker (`modules/operations/.../recovery/OperatorRetryWorker.java`) run as
  `@Scheduled` tasks inside the same process, gated by `LEDGERFLOW_RECOVERY_WORKER_ENABLED`
  (`.env.example`). Extensions 3 and 4 (which assume "API and worker" as separate
  deployables) must treat "worker" as a second deployment of the **same image**, driven by
  configuration, not a second build artifact. This is recorded here rather than assumed.
- Verification lifecycle already exists and is complete: `./gradlew verify` depends on
  `spotlessCheck`, `staticAnalysis`, `test`, `integrationTest` (PostgreSQL **and** Kafka via
  Testcontainers, `application/build.gradle.kts:88`), `architectureTest` (Spring Modulith +
  ArchUnit), `openApiValidate`, `mockProviderOpenApiValidate`, `composeValidate`,
  `observabilityValidate`, `documentationCheck` (`build.gradle.kts:332`). Extension 1 does
  not need to reinvent these checks — it needs to run them in CI.
- `scripts/security-scan` (`scripts/security-scan:1`) already performs Trivy filesystem
  scanning (secrets + misconfig), Trivy rootfs scanning of packaged Java dependencies, and
  Trivy image scanning of every Compose image against a digest-bound, expiring exception
  policy (`config/security/local-compose-vulnerability-exceptions.json`) with no exception
  path for repository secrets or the packaged application. This is a separate Gradle task
  (`securityScan`, `build.gradle.kts:349`) deliberately excluded from `verify` because it
  needs privileged Docker-socket access and downloads fresh vulnerability intelligence
  (`docs/development-workflow.md:103`). CI must run it as a distinct job with the same
  privilege awareness, not fold it into the PR gate blindly.
- No `.github/` directory exists. No CodeQL, no SBOM generation, no Dockerfile, no
  `.dockerignore`, no branch-protection documentation exist yet.
- `AGENTS.md` and `.agent/PLANS.md` govern this repository: one milestone in progress at a
  time, explicit maintainer approval per milestone, no unrelated cleanup, `./gradlew clean
  verify` as the completion bar, forward-only migrations. This plan follows those rules for
  every milestone below, in addition to satisfying the extension prompt's own per-milestone
  gates.
- **History**: this exact 7-extension scope was previously attempted twice via a
  Codex-driven multi-subagent workflow, fully merged once, then reverted to the pre-extension
  MVP baseline (commit `edaa813`) with no rationale recorded. That prior attempt is not
  reused as a starting point (maintainer instruction, 2026-07-18): it committed two CLI
  binaries directly into git (~12 MB), added an out-of-scope operator dashboard UI never
  requested by any extension, and implemented only 2 of the 11 required performance
  scenarios while documentation implied broader coverage. This plan is a from-scratch
  rebuild informed by, but not copying, that history.

## Scope and non-goals

In scope: the seven extensions below, executed sequentially, one in progress at a time.

Out of scope / explicit non-goals:

- Rewriting, reordering, or reinterpreting any completed MVP milestone, ADR, or migration.
- Any destructive or irreversible repository/GitHub setting change (branch protection rules,
  required-review rules, secret-scanning toggles) — these are documented as recommendations
  only, never applied via API or `gh` CLI, per the extension prompt's own constraint.
- A service mesh (explicitly excluded from Extension 4).
- Running `terraform apply`, creating real cloud resources, or inserting real account IDs
  (explicitly excluded from Extension 5).
- Fabricated benchmark results or invented evidence anywhere in Extensions 2 or 7; any
  experiment that cannot be safely automated is explicitly marked manual-execution-required
  instead of faked.
- Reusing or reading from `archive/main-before-d2f1721-2026-07-17` (maintainer instruction,
  2026-07-18).

## Interfaces and data

None of the seven extensions change the public HTTP contract, Java module APIs, or add a
Flyway migration to the `application` module. Extension 6 (AI assistant) is a separate
Python FastAPI service with its own contract, documented when that milestone starts.
Container and Kubernetes manifests introduced by Extensions 3–4 are new deployment
artifacts, not application interfaces.

## Milestones

### Milestone 1 — CI/CD and software supply chain

- Status: Complete
- Intended outcome: Every pull request runs a security-gated CI pipeline (format, static
  analysis, unit/Postgres/Kafka/architecture tests, OpenAPI + doc checks, migration
  validation via the existing `integrationTest` Flyway-from-empty-database path, CodeQL,
  secret scanning, Trivy filesystem/image scanning, SBOM generation, an OCI image build) with
  least-privilege permissions, pinned actions, cancellation of superseded runs, uploaded test
  reports on failure, and no deployment capability from untrusted PRs. Branch-protection
  recommendations are documented, not applied.
- Implementation work:
  - `.github/workflows/ci.yml`: PR/push-to-main trigger; top-level
    `permissions: contents: read`; `concurrency` group with `cancel-in-progress: true`; jobs
    for `verify` (wraps `./gradlew clean verify`, needs Docker for Testcontainers), image
    build (build-only on PRs, no push/registry auth available to fork PRs), and
    artifact/test-report upload on failure.
  - `.github/workflows/codeql.yml`: CodeQL analysis for Java, `security-events: write`
    permission scoped to that job only.
  - `.github/workflows/security-scan.yml`: runs `scripts/security-scan` on push to `main`
    and on a schedule (matches the script's own documented intent), not on arbitrary fork
    PRs, since it needs privileged Docker-socket access.
  - SBOM generation (CycloneDX, via Syft) for the built jar/image, uploaded as a workflow
    artifact.
  - `Dockerfile` (multi-stage, Java 25 JRE runtime, non-root `USER`, `.dockerignore`),
    sufficient for the CI image-build step; Extension 3 hardens and extends this rather than
    replacing it.
  - Every third-party action pinned to a full commit SHA with a version comment.
  - `docs/branch-protection.md`: documents recommended required status checks, required
    review count, and no-force-push-to-main — as a recommendation, not applied.
- Validation commands: `./gradlew clean verify`; `docker build .`; `actionlint
  .github/workflows/*.yml` if available locally; a real PR (or `act`/workflow dry-run) to
  confirm the workflow parses and the concurrency/permissions blocks are valid.
- Observable acceptance criteria: opening a PR against this branch's remote counterpart
  triggers the CI workflow; all existing local gates pass identically in CI; CodeQL and SBOM
  artifacts are produced; a second push to the same PR cancels the in-flight run; a
  deliberately failing test produces an uploaded test report; no job has write access to
  packages/registries when triggered by `pull_request` from a fork.

### Milestone 2 — Performance and failure experiments

- Status: Proposed
- Intended outcome: k6 (or equivalent) scenarios for the 11 listed workloads, each with a
  recorded hypothesis, workload, test data, expected behavior, threshold, and an honestly
  observed result (or an explicit manual-execution marker where automation is unsafe, e.g.
  scenarios that stop Docker containers).
- Detail deferred until this milestone is approved to start, per `.agent/PLANS.md` (avoid
  prescribing implementation before the current milestone is complete and reviewed).

### Milestone 3 — Production-oriented containers

- Status: Proposed
- Intended outcome: hardened multi-stage image(s) building on Milestone 1's Dockerfile —
  read-only root filesystem compatibility, bounded writable temp storage, graceful shutdown,
  OCI metadata labels, reproducible-build guidance, SBOM, vulnerability scanning, no embedded
  secrets, documented JVM resource settings. "Worker" is realized as a second deployment of
  the same image with `LEDGERFLOW_RECOVERY_WORKER_ENABLED` and equivalent config, per the
  Current State finding above — not a second build artifact.
- Detail deferred until approved to start.

### Milestone 4 — Local Kubernetes and Helm

- Status: Proposed
- Intended outcome: `kind` dev cluster, Helm chart, separate API/worker Deployments (same
  image, different config per the Milestone 3 finding), probes, ConfigMaps, local-only secret
  placeholders, resource limits, security contexts, dropped capabilities, read-only root
  filesystem, PDB, API HPA, NetworkPolicies, least-privilege ServiceAccounts, lint/template
  validation, one-command deploy, e2e smoke test. No service mesh.
- Detail deferred until approved to start.

### Milestone 5 — AWS Terraform design

- Status: Proposed
- Intended outcome: validated (`terraform fmt`/`validate`, `tflint`, Checkov-or-equivalent),
  never-applied two-AZ VPC/ECS Fargate/RDS/ECR/Secrets Manager/CloudWatch design with cost
  tags, a cost estimate, remote-state design, and teardown instructions. No real account IDs,
  no `terraform apply`, no cloud resources created.
- Detail deferred until approved to start.

### Milestone 6 — Optional AI operations assistant

- Status: Proposed
- Intended outcome: separate Python FastAPI service, deterministic fake provider as the
  default with no API key required for local tests, optional OpenAI Responses API provider,
  curated runbook retrieval, sanitized telemetry input, structured incident-summary output
  with evidence/confidence/uncertainty/suggested steps/cited runbooks, no automatic
  remediation, strict token/timeout/cost bounds, prompt-injection-resistant context
  separation, ≥20 evaluation fixtures, latency/cost metrics, tests proving secrets are never
  sent. Fake provider stays default unless the maintainer explicitly configures API billing.
- Detail deferred until approved to start.

### Milestone 7 — Final portfolio release

- Status: Proposed
- Intended outcome: polished technical-interviewer README, architecture diagrams, demo video
  script, screenshots guide, résumé bullets grounded only in implemented evidence, interview
  discussion guide, documented trade-offs/rejected alternatives, local setup verification, CI
  evidence, performance evidence, Kubernetes evidence, Terraform validation evidence, AI
  evaluation evidence, final residual risks, no inflated production-scale claims. Every
  validation and security gate run for real before this milestone is marked complete.
- Detail deferred until approved to start.

## Implementation approach

Milestones execute strictly sequentially; only one is `In Progress` at a time
(`.agent/PLANS.md`). Per the maintainer's approved execution style, each milestone is
implemented directly (not delegated to a per-domain subagent swarm, which is a plausible
contributor to the previous attempt's scope creep and stray artifacts), gated by
`./gradlew clean verify` plus its own extension-specific checks, committed with the exact
message specified in the original extension request, and pushed to
`origin feat/portfolio-extensions` — then this session stops for maintainer review before
the next milestone starts. The final tag (`v1.1.0-portfolio`) and push to `main` require
separate explicit confirmation after Milestone 7, since those actions are harder to reverse
and affect the shared remote.

## Validation and acceptance

Every milestone runs `./gradlew clean verify` at minimum before being marked complete;
milestone-specific commands are listed under each milestone above and will be filled in for
Milestones 2–7 as they become current. Milestone 7 additionally requires every gate from
every prior milestone to pass together as the final release check.

## Rollback and recovery

Each milestone is one commit (or a small, reviewable set) on `feat/portfolio-extensions`,
pushed after passing its gates. A milestone found to be wrong after review is reverted with
`git revert`, never by editing or dropping the commit, so the branch history stays honest.
No milestone touches an already-merged Flyway migration; any schema change (not currently
anticipated by any extension) would use a new forward migration only.

## Progress

- [x] `2026-07-18` — Repository investigation complete: reconstructed prior extension
  history (two attempts, one merged then reverted), confirmed no CI/Docker/Terraform/Helm
  artifacts currently exist, confirmed single-deployable-module architecture (no separate
  worker build), confirmed existing `verify`/`securityScan` task coverage. Plan drafted and
  approved for Milestone 1 start.
- [x] `2026-07-18` — Milestone 1 implemented: `Dockerfile` (multi-stage, Java 25 Temurin
  Alpine, digest-pinned base images, non-root `ledgerflow:ledgerflow` user, confirmed via
  `docker inspect`), `.dockerignore`, `.github/workflows/ci.yml` (verify job wrapping
  `./gradlew clean verify`; image job building the OCI image with `docker/build-push-action`
  `push: false`, generating a CycloneDX SBOM via `anchore/sbom-action`, and Trivy-scanning
  the built image with the same pinned Trivy image `scripts/security-scan` uses),
  `.github/workflows/codeql.yml` (java-kotlin, manual build mode), `.github/workflows/security-scan.yml`
  (runs `scripts/security-scan` on push-to-main and schedule only, not on pull requests),
  `.github/dependabot.yml` (github-actions/gradle/docker ecosystems),
  `docs/branch-protection.md`, and a short `README.md` pointer section. Every third-party
  GitHub Action is pinned to a full commit SHA resolved live via `git ls-remote` against
  each action's repository on 2026-07-18.
- [x] `2026-07-18` — Milestone 1 validated: `docker build .` succeeds and
  `docker inspect --format '{{.Config.User}}'` confirms `ledgerflow:ledgerflow` (non-root);
  `./gradlew --no-daemon clean verify` passes (`BUILD SUCCESSFUL`, includes
  `documentationCheck` validating the new README/docs links); `actionlint` (downloaded
  to the scratchpad, not committed) reports no issues against all three workflow files;
  `./scripts/security-scan` exits 0 — repository-secret and packaged-application gates
  pass, and the only Compose-image findings are on third-party images (Keycloak, Valkey)
  already covered by pre-existing, unexpired local-development risk records unrelated to
  this milestone's changes.

## Surprises and discoveries

- The application has no separate "worker" process today; Extensions 3 and 4's "API and
  worker" language must map to configuration-differentiated deployments of one image, not
  two build artifacts. Recorded above so Milestones 3–4 don't assume a nonexistent artifact.
- `scripts/security-scan` and the `verify` lifecycle already implement most of what
  Extension 1 asks for locally; the actual net-new work is CI wiring, CodeQL, SBOM, and the
  Dockerfile — not reimplementing quality gates that already exist.
- The previous (reverted) extension attempt left two CLI binaries committed to git history
  on `archive/main-before-d2f1721-2026-07-17` (~12 MB) and an unrequested demo UI. Neither is
  reused; noted so the same mistake isn't repeated.

## Decision log

- 2026-07-18 — Rebuild the seven extensions from scratch instead of cherry-picking the
  archived attempt. Rationale: the archive has specific, identifiable defects (binaries in
  git, out-of-scope UI, incomplete performance coverage) and the maintainer directed not to
  use that branch. No ADR required — this is portfolio tooling, not an MVP architecture
  decision.
- 2026-07-18 — Execute with maintainer checkpoint after each milestone rather than the
  original prompt's "do not wait for approval between them." Rationale: matches this
  repository's own `.agent/PLANS.md` governance (one milestone in progress, explicit
  approval required) and directly addresses a plausible cause of the previous attempt's
  quality problems. No ADR required.
- 2026-07-18 — Implement each milestone directly rather than via per-domain subagents.
  Rationale: the previous attempt's subagent branch names (e.g.
  `subagent-CI-CD-and-DevSecOps-Expert-...`) suggest a multi-agent-per-domain pattern that
  correlates with the scope creep and stray artifacts found in that history. No ADR
  required.
- 2026-07-18 — Use `eclipse-temurin:25-jdk-alpine` / `25-jre-alpine`, pinned by digest, as
  the Dockerfile base images. Rationale: Alpine has a materially smaller package set than
  Debian-based Temurin images, which reduces Trivy's OS-vulnerability surface and scan
  time; Temurin is already the JDK distribution implied by the rest of the toolchain
  (`docs/development-workflow.md` uses Java 25 throughout, no other distribution is
  referenced). Digest pinning matches the existing pattern already used for the Trivy
  scanner image in `scripts/security-scan`. `.github/dependabot.yml`'s `docker` ecosystem
  entry keeps these pins current. No ADR required — this is deployment tooling, not an
  application architecture decision.
- 2026-07-18 — Do not commit an actionlint (or any other CLI tool) binary to the
  repository to validate workflow YAML. Rationale: the reverted prior attempt committed
  two such binaries (~12 MB) directly into git history; downloading actionlint to the
  session scratchpad for one-off validation gets the same assurance without repeating that
  mistake. No ADR required.

## Outcome and follow-up

Not yet complete. Updated as each milestone finishes.
