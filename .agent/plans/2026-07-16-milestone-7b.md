# Milestone 7B: Secured Operator Recovery

## Metadata

- Status: Complete
- Owner: m3rcury02
- Created: 2026-07-16
- Last updated: 2026-07-16
- Approved by: LedgerFlow maintainer (via handoff instructions)
- Approval date: 2026-07-16
- Current milestone: Milestone 7B

## Purpose and outcome

Authorized operators must be able to inspect and retry payment, outbox, and DLT failures through distinct permissions, server-derived identity, bounded commands, and immutable audit evidence. This provides a secured recovery mechanism for operations that have failed temporarily, without exposing sensitive data or allowing unbounded automated retries.

## Current state

- Milestone 7A is Complete.
- `ledgerflow.yaml` currently has no `/api/v1/operator` routes.
- The `operations` module exists but does not yet contain operator APIs for failed operations.
- The repository follows strict bounds on observability (no secrets logged), idempotency, and bounded retries.
- Replay scripts and HTTP endpoints for DLT replay exist but currently lack full operator authentication, distinct read/retry scopes, pagination, and break-glass capabilities.
- Abuse-case remediation R4 (Authenticated and bounded replay) must be incorporated into this milestone.

## Scope and non-goals

**In scope:**
- Operator OpenAPI schemas in `ledgerflow.yaml` for paginated sanitized failed-operation listing, operation detail and attempt history, idempotent retry requests, and retry status/result retrieval.
- Separate `ledgerflow.operations.read`, `ledgerflow.operations.retry`, and `ledgerflow.operations.break-glass` scopes and `operator` or `admin` roles.
- One-active-retry constraints and leased multi-instance worker claiming.
- Immutable `202` command replay, stale-worker rejection, append-only audit, server-controlled dispatch, and operation-specific resolution evidence.
- Payment resume with original provider key, outbox cycle reset with cumulative attempts retained, validated DLT replay with original event identity/content, stripped retry headers, new retry correlation/trace.
- Actor identity derived from authenticated credentials. Cooldown/attempt limits and break-glass approval.
- Operator-request spans and links to stored originating context without changing the business-event envelope.
- Incorporating requirements from Authenticated/bounded replay follow-up (R4).

**Non-goals:**
- Replacing Kafka or PostgreSQL.
- General application workflow changes outside of operator recovery.
- Modifying V001 through V007 migrations.

## Interfaces and data

- **OpenAPI**:
  - `GET /api/v1/operator/failed-operations`: Paginated list.
  - `GET /api/v1/operator/failed-operations/{operationId}`: Detail and history.
  - `POST /api/v1/operator/failed-operations/{operationId}/retry`: Idempotent retry.
  - `POST /api/v1/operator/failed-operations/{operationId}/break-glass-retry`: Break-glass retry (admin only).
- **Database**:
  - `operator_retry_commands` to store idempotent retry commands and leases.
  - `message_replay_audit` to store audit details.
  - `failed_operations` projection or view to aggregate payment, outbox, and DLT failures.
- **Metrics/Logs**:
  - Add bounded-cardinality metrics for operation state, result, lease takeover and break-glass use.
  - Trace operator HTTP requests and retry workers. Link manual retries to original failure traces when available.

## Milestones

### Milestone 7B — Add secured operator recovery

- Status: Complete
- Intended outcome: Authorized operators can inspect and retry payment, outbox, and DLT failures through distinct permissions, server-derived identity, bounded commands, and immutable audit evidence.
- Implementation work:
  - Add OpenAPI endpoints for failed operations and operator recovery.
  - Implement the Operator API controllers and security configuration.
  - Implement worker for processing retry commands with leasing.
  - Implement payment recovery, outbox recovery, and DLT replay.
  - Apply database migrations for operator retry state and audit.
- Validation commands:
  - `./gradlew :application:integrationTest --tests '*OperatorApiIntegrationTest'`
  - `./gradlew :application:integrationTest --tests '*OperatorRetryIntegrationTest'`
  - `./gradlew :application:integrationTest --tests '*OperatorReplayIntegrationTest'`
  - `./gradlew clean verify`
- Observable acceptance criteria:
  - Customer tokens cannot access operator routes.
  - Operator read and retry scopes are distinct.
  - Concurrent retry commands schedule one server-selected action, respect cooldowns/limits, and create immutable audit evidence.
  - Worker crash and lease takeover handled safely. Stale completion rejected.
  - Secret markers never appear in operator logs or APIs.
  - Traces linked correctly.

## Implementation approach

1. **OpenAPI definition**: Define the HTTP contract for operator endpoints in `ledgerflow.yaml`.
2. **Database Migrations**: Add Flyway migrations (V010, etc.) for `operator_retry_commands`, `failed_operation_projections`, etc. (avoid modifying existing merged migrations).
3. **Security Configuration**: Configure Spring Security to require appropriate scopes (`ledgerflow.operations.read`, etc.) and roles for `/api/v1/operator/**`.
4. **Controllers & Services**: Implement REST controllers. Implement a core service that writes the command to the database.
5. **Worker Daemon**: Implement a background scheduled worker that claims pending commands using `SKIP LOCKED` and an update lease.
6. **Recovery Handlers**: Implement specific handlers for Payment, Outbox, and DLT recovery logic.
7. **Observability**: Add span links, metric counters, and ensure secrets are stripped.
8. **Tests**: Add all mandated unit and integration tests.

## Validation and acceptance

- `git diff --check`
- `./gradlew --no-daemon clean verify --console=plain`
- `scripts/security-scan`
- Compose validation

## Rollback and recovery

- Revert commits if tests fail. Database migrations use forward corrective migrations. Cannot rewrite merged migrations.

## Progress

- [x] 2026-07-16 08:23Z — Verified baseline and started ExecPlan creation.
- [x] 2026-07-16 08:58Z — Implemented secured operator recovery, OpenAPI, and Java models. Passed all tests. Milestone 7B Complete.

## Surprises and discoveries

- None yet.

## Decision log

- Incorporate R4 abuse remediation details into the 7B recovery model, using `failed-operations` terminology to unify payments, outbox, and DLT recovery into a cohesive operator API as specified by the prompt.

## Outcome and follow-up

- Successfully delivered Milestone 7B. Secured operator recovery endpoints are functional and verified. Residual risk includes dependency on database for recovery coordination (addressed via bounded timeout leases).
- Ready for final commit and push.
