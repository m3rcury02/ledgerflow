# LedgerFlow Agent Instructions

These instructions apply to the entire repository.

## Scope and approval

- Work on only one approved implementation milestone at a time.
- A direct, scoped maintainer request approves one simple milestone. Do not infer approval for additional work.
- Significant or multi-step work requires an ExecPlan following `.agent/PLANS.md`. Implementation may start only after its current milestone is explicitly approved.
- Keep changes within the approved milestone. Avoid unrelated refactoring, cleanup, renaming, dependency upgrades, or formatting churn.
- Preserve unrelated existing changes. If they prevent safe progress, stop and report the conflict.

## Technical baseline

- Use Java 25 and Spring Boot 4.1.
- Use Gradle Kotlin DSL and the committed Gradle Wrapper. Run builds through `./gradlew`, never a system Gradle installation.
- Organize application code package-by-feature. Do not create repository-wide controller, service, repository, or model packages.
- Build one deployable modular monolith. Feature-module boundaries and dependency rules must be verified by automated architecture tests.
- Apply hexagonal architecture only within a module where isolation from external systems or domain complexity clearly justifies it. Record the reason in the ExecPlan or relevant ADR.
- Use PostgreSQL in production and PostgreSQL Testcontainers for integration tests. Do not use H2.
- Manage schema changes with Flyway. Never modify, rename, reorder, or delete a migration after it has been merged; add a new migration instead.
- Define HTTP APIs in the version-controlled OpenAPI contract before or with their implementation.
- Represent money as an integer number of minor units plus an uppercase ISO 4217 currency code. Never use `float` or `double` for monetary values.
- Persist timestamps as `Instant` in UTC. Use another temporal type only when the domain concept is not an instant, such as a calendar date.
- Externally retried write operations must implement and test idempotency.
- Emit structured production logs, propagate or create a correlation ID at inbound boundaries, and never log secrets or sensitive financial payloads.
- Never commit secrets. Use environment variables or an approved secret store; committed examples must contain placeholders only.

## Dependencies and changes

- Do not add a production dependency unless the change records why it is necessary, why existing platform capabilities are insufficient, and its operational or security implications.
- Update tests, OpenAPI contracts, architecture documentation, ADRs, and operational documentation whenever their described behavior changes.
- Do not disable, weaken, or bypass a quality check to make a change pass without explicit maintainer approval and documented justification.

## Completion

A task is complete only when the approved acceptance criteria are met and `./gradlew clean verify` passes. The `verify` lifecycle must cover:

- formatting checks;
- static analysis;
- unit tests;
- PostgreSQL integration tests;
- architecture-boundary checks;
- OpenAPI validation; and
- documentation checks.

Follow `docs/development-workflow.md` for commands and `docs/definition-of-done.md` for the full completion criteria.
