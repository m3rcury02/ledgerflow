# Definition of Done

A LedgerFlow task is done only when every applicable item below is true and the complete verification lifecycle passes. “Follow up later” does not satisfy this definition unless the item is explicitly outside the approved milestone.

## Scope and acceptance

- The work belongs to the single approved milestone.
- Every acceptance criterion has observable evidence.
- No unrelated refactoring, cleanup, renaming, dependency upgrade, or formatting churn is included.
- Any required implementation plan is current, including progress, discoveries, decisions, and outcome.
- Newly discovered follow-up work is recorded separately and is not implemented without approval.

## Implementation

- Behavior is implemented at the correct feature boundary.
- Cross-feature access uses the owning feature's API.
- Hexagonal structure is used only where its justification is recorded.
- Error handling covers expected failure modes without exposing secrets or internals.
- Authentication and authorization changes fail closed and include negative tests for missing, invalid, and insufficient identity.
- New production dependencies include the required justification.
- No quality check has been disabled, weakened, or bypassed.

## Contracts and data

- Changed HTTP behavior is represented accurately in OpenAPI.
- Compatibility and client migration effects are documented where relevant.
- Database changes use new Flyway migrations.
- No migration already present in merge history has been edited, renamed, reordered, or deleted.
- Migrations work against an empty PostgreSQL database and relevant existing-data states.
- Externally retried writes have tested idempotency behavior.
- Monetary values use integer minor units and ISO 4217 currency codes.
- Persisted timestamps use `Instant` and UTC unless the value is explicitly a different domain concept.

## Testing and quality

- Formatting checks pass.
- Static analysis passes without new suppressions lacking justification.
- Unit tests cover business rules and failure behavior.
- PostgreSQL Testcontainers integration tests cover persistence and external boundaries.
- Architecture tests pass and cover any new module relationship.
- OpenAPI validation and affected contract tests pass.
- Changed telemetry has in-memory propagation, redaction, bounded-cardinality, and exporter-failure tests; Prometheus rules and Grafana/Collector provisioning validate with pinned images.
- Tests are deterministic and do not depend on execution order, local time zone, or the developer's machine.
- No required test is ignored, quarantined, or conditionally skipped.
- Security-sensitive input limits, rate limits, and redaction rules have focused boundary tests when applicable.

## Documentation and operations

- Relevant architecture, workflow, API, configuration, and operational documentation matches the delivered behavior.
- Significant architectural decisions have an accepted or explicitly proposed ADR as appropriate.
- Production logs are structured and include correlation IDs at relevant boundaries.
- Logs and error messages do not expose secrets or unnecessary sensitive data.
- Configuration and examples contain placeholders rather than real credentials.
- Security, dependency, build-image, or Compose-image changes pass `./scripts/security-scan` with no committed-secret or packaged-application exception and no unapproved, stale, or expired Compose finding. Local Compose acceptance must be exact, digest-bound, documented, expiring, and never represented as production acceptance.
- Operational failure and recovery behavior is documented for risky changes.
- Every new alert uses an exported bounded metric and links to a version-controlled runbook with diagnosis, impact, safe actions, escalation, and recovery verification.

## Final verification

The following commands have run successfully:

```text
./gradlew --version
./gradlew clean verify
```

When the change affects security, dependencies, or container images, this command has also run successfully:

```text
./scripts/security-scan
```

The final review confirms:

- the wrapper used Java 25;
- `verify` executed formatting, static analysis, unit tests, integration tests, architecture checks, OpenAPI validation, and documentation checks;
- the diff contains only approved changes; and
- the change summary identifies delivered behavior, verification evidence, migrations, dependencies, documentation, and known limitations.

For the MVP release, `scripts/smoke-test` and `scripts/demo-mvp` also pass, AC-001 through AC-016
map to evidence in `docs/mvp-evidence.md`, the complete repository review has no unresolved
Critical/High or material Medium defect, and the residual-risk/operational-limit documents do not
represent future or production work as delivered.

If any required check cannot run, the task is not done. Record the blocker and the exact condition needed to complete verification.
