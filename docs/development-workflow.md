# Development Workflow

## 1. Confirm scope and approval

Before changing files:

1. Read this workflow, relevant architecture documentation, and applicable ADRs.
2. Inspect the working tree and preserve unrelated changes.
3. Identify the single approved milestone.
4. For significant or multi-step work, create or update an ExecPlan and wait for explicit approval.
5. Write observable acceptance criteria before implementation.

A request does not authorize adjacent cleanup, dependency upgrades, speculative abstractions, or later milestones.

## 2. Establish the current state

Use repository evidence rather than assumptions:

- locate the owning feature and its public API;
- inspect relevant OpenAPI operations and Flyway migrations;
- identify existing unit, integration, and architecture tests;
- check callers before changing a shared type or contract; and
- record important discoveries in the ExecPlan.

If the requested behavior conflicts with an accepted ADR or repository rule, stop and surface the conflict instead of silently bypassing it.

## 3. Implement within a feature boundary

Keep changes inside the owning feature wherever possible. Cross-feature interaction goes through the owning feature's API.

Use the simplest internal design that satisfies the behavior. Introduce ports and adapters only when the conditions in `docs/architecture.md` are met.

Write or update tests with the behavior. Do not defer testing, contracts, migrations, or documentation to an unapproved follow-up milestone.

## 4. Change contracts and persistence safely

For HTTP behavior:

1. update `application/src/main/openapi/ledgerflow.yaml`;
2. validate the contract;
3. implement the behavior; and
4. add contract-focused integration tests.

For database behavior:

1. add a new Flyway migration;
2. never edit a migration already present in the merge history;
3. test migration from an empty PostgreSQL database;
4. test compatibility with existing data where relevant; and
5. document rollout and recovery for destructive or long-running changes.

Externally retried writes must include idempotency behavior and tests. Money and timestamps must follow `docs/architecture.md`.

## 5. Evaluate dependencies

Before adding a production dependency, record:

- the capability required;
- why the JDK, Spring Boot, or an existing dependency is insufficient;
- considered alternatives;
- maintenance, licensing, security, runtime, and operational impact; and
- how the dependency will be tested and updated.

Put this reasoning in the ExecPlan, ADR, or change description. Prefer Spring Boot-managed versions. Do not add a library merely to avoid a small, clear implementation.

## 6. Run focused validation

Use the committed wrapper for every Gradle command.

During development, run the smallest relevant checks:

```text
./gradlew spotlessCheck
./gradlew staticAnalysis
./gradlew test
./gradlew integrationTest
./gradlew architectureTest
./gradlew openApiValidate
./gradlew composeValidate
./gradlew observabilityValidate
./gradlew documentationCheck
./scripts/security-scan
./scripts/smoke-test
./scripts/demo-mvp
```

Expected task responsibilities:

- `spotlessCheck`: verify formatting without rewriting files.
- `staticAnalysis`: aggregate the configured Java and build-script analyzers.
- `test`: run fast unit tests without external services.
- `integrationTest`: run PostgreSQL Testcontainers tests, including Flyway migrations.
- `architectureTest`: enforce module and architectural constraints.
- `openApiValidate`: validate every OpenAPI document.
- `composeValidate`: resolve and validate `compose.yaml` using the non-secret `.env.example` defaults.
- `observabilityValidate`: validate Collector configuration, Prometheus configuration and rules, all alert/runbook links, Grafana data sources, and all provisioned dashboards with the pinned Compose images.
- `documentationCheck`: check Markdown formatting, internal links, and required document structure.
- `scripts/security-scan`: build the executable artifact and use the pinned container scanner for repository secrets/misconfiguration, packaged Java dependencies, and every Compose image.
- `scripts/smoke-test`: run one complete public HTTP-to-notification acceptance journey against ephemeral PostgreSQL and Kafka.
- `scripts/demo-mvp`: run the focused MVP failure/recovery evidence matrix plus graceful-drain proof.
- `verify`: depend on every task above.

`integrationTest`, `composeValidate`, `observabilityValidate`, `verify`, and `scripts/security-scan` require a working Docker-compatible container runtime with Docker Compose. The security scan is intentionally separate from `verify` because it downloads vulnerability intelligence, scans large images, and needs read-only access to the privileged Docker socket. Run it for security, dependency, build-image, or Compose-image changes and in the scheduled CI security workflow.

The security scan fails on fixed HIGH or CRITICAL findings and committed-secret detections. Repository-secret and packaged-application gates have no exception path. The only current Compose exception mechanism is the exact, digest-bound, expiring local-development policy linked to the [container risk register](security/local-development-container-risk-register.md). It prints all Trivy findings and rejects new, changed, stale, undocumented, or expired tuples; it is prohibited for production. Do not add a broad ignore or copy a local acceptance into another scanner. Refresh scanner intelligence on every execution and protect the CI runner because Docker-socket readers can inspect host containers and images.

Operate local dependencies through `scripts/dev-up`, `scripts/dev-down`, `scripts/dev-reset`, and `scripts/dev-status`. `dev-reset` is destructive and deletes all local named volumes; it never applies to a shared or production environment.

Use `./gradlew spotlessApply` only as an intentional local edit, then inspect its diff. Never use automatic formatting to rewrite unrelated files.

## 7. Complete verification

Before declaring a task complete, run from a clean build state:

```text
./gradlew --version
./gradlew clean verify
```

For a change that affects security controls, dependencies, or container images, also run:

```text
./scripts/security-scan
```

Confirm that:

- Gradle is running through the wrapper on Java 25;
- every required task ran and passed;
- no test or check was skipped, excluded, or weakened;
- the working-tree diff contains only approved changes;
- relevant documentation and contracts match the delivered behavior; and
- every acceptance criterion has observable evidence.
- the applicable supply-chain scan completed with no unapproved finding.

Record the commands and results in the ExecPlan or change summary.

For an MVP release candidate, also update `docs/mvp-evidence.md`, run `scripts/smoke-test` and
`scripts/demo-mvp`, review dependencies/migrations/ADRs/residual risks, and record the exact final
evidence in the canonical ExecPlan. A tag is created only after every required check passes.

## Bootstrap requirement

The repository initially has no Gradle Wrapper or verification lifecycle. The first approved application-bootstrap milestone must create the wrapper and all named verification tasks before any application implementation milestone can be declared complete.

Quality-tool selection must be compatible with Java 25 and Spring Boot 4.1. The tools may change later without changing this workflow, provided the stable task names and equivalent enforcement remain intact.
