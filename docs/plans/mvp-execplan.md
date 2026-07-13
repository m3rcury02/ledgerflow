# Deliver the LedgerFlow MVP Financial Event Flow

## Metadata

- Status: In Progress
- Owner: Codex
- Created: 2026-07-11
- Last updated: 2026-07-13
- Approved by: LedgerFlow maintainer
- Approval date: 2026-07-13
- Current milestone: Milestone 5A — Immutable ledger posting (`Complete`); no later milestone is approved
- Canonical plan path: `docs/plans/mvp-execplan.md` by explicit maintainer request

## Purpose and outcome

Build a production-grade portfolio backend that visibly and safely completes this flow:

```text
POST order
  -> authorize and capture through an external HTTP mock provider
  -> atomically complete payment/order, post balanced ledger entries, and write outbox
  -> publish the outbox event to Kafka at least once
  -> consume idempotently with bounded retries and DLT
  -> create exactly one notification record
```

The outcome is observable through HTTP responses, current order state, PostgreSQL records, Kafka topics, structured logs, OpenTelemetry traces, and a secured operator recovery API. Correctness must survive duplicate HTTP requests, concurrent requests, provider ambiguity, transaction rollback, duplicate Kafka delivery, consumer crashes, poison messages, and repeated operator commands.

The normative requirements and designs are:

- `docs/product-requirements.md`
- `docs/domain-model.md`
- `docs/api-design.md`
- `docs/data-model.md`
- `docs/threat-model.md`
- accepted decisions in ADRs 0003 through 0005 and proposed ADRs 0002 and 0006 through 0008 under `docs/adr`

## Current state

The repository contains the verified foundation and completed Create Order, payment/provider, and ledger slices from Milestones 1 through 5A:

- `AGENTS.md` establishes Java 25, Spring Boot 4.1, Gradle Kotlin DSL, modular-monolith, PostgreSQL, Flyway, OpenAPI, money/time, idempotency, observability, scope, and quality rules.
- `.agent/PLANS.md` defines ExecPlan structure and one-approved-milestone discipline.
- `settings.gradle.kts`, the root build, and the Gradle 9.6.1 Wrapper define one deployable `application` project and six feature-library projects under `modules`.
- `application/src/main/java/com/ledgerflow/LedgerFlowApplication.java` is the Spring Boot 4.1 entry point; `modules/orders`, `modules/payments`, and `modules/ledger` contain implemented business behavior.
- `application/src/main/openapi/ledgerflow.yaml` defines active authenticated create/read order contracts and RFC 9457-style problems.
- `V001__create_orders_and_idempotency.sql`, `V002__create_payment_tables.sql`, and `V003__create_immutable_ledger.sql` create the implemented order, HTTP-idempotency, payment, payment-attempt, and ledger schemas.
- `application/src/integrationTest` proves context loading, Flyway startup, repository constraints, HTTP idempotency, provider recovery, and payment concurrency against PostgreSQL 18.4 Testcontainers.
- `application/src/architectureTest` verifies exactly six Spring Modulith modules and complementary ArchUnit package rules.
- `compose.yaml`, `.env.example`, `infra`, and `scripts/dev-*` provide the pinned nine-service local environment, safe placeholder defaults, provisioning, and lifecycle commands.
- `build.gradle.kts` provides the stable formatting, static-analysis, unit, integration, architecture, OpenAPI, Compose, documentation, and aggregate `verify` tasks.
- `docs/architecture.md`, `docs/development-workflow.md`, `README.md`, and this plan record the approved bootstrap choices and developer commands.
- ADR 0001 establishes the ADR process; ADR 0003 is accepted for Create Order, ADR 0004 for the provider/payment state machine, and ADR 0005 for ledger posting.

The maintainer's 2026-07-13 ledger-only request accepts ADR 0005 and approves Milestone 5A below. It deliberately excludes order transitions, outbox, Kafka, notifications, public routes, and operator APIs. ADRs 0002 and 0006 through 0008 remain proposed.

The maintainer explicitly requested this ExecPlan at `docs/plans/mvp-execplan.md`. This is a one-plan path exception to `.agent/PLANS.md`, which normally specifies `.agent/plans/YYYY-MM-DD-<name>.md`. Do not create a duplicate plan.

## Scope and non-goals

### In scope

- One multi-project Gradle Kotlin DSL build producing a single Spring Boot application using Java 25 and base package `com.ledgerflow`.
- Feature modules `orders`, `payments`, `ledger`, `messaging`, `notifications`, and `operations` with automated boundary tests.
- Contract-first OpenAPI documents for LedgerFlow `/api/v1` and the local/test mock-provider HTTP boundary.
- OAuth 2.0 JWT resource-server validation and scopes from `docs/api-design.md`; the identity provider is external.
- One positive full-capture INR order and one payment.
- A test/local HTTP mock payment provider with stable operation keys and deterministic faults.
- PostgreSQL 18, Flyway, explicit Spring `JdbcClient` SQL, and PostgreSQL Testcontainers.
- Immutable balanced double-entry posting for confirmed capture.
- Transactional outbox, Kafka publication, inbox deduplication, notification persistence, retry topics, DLT catalog, and operator replay.
- Correlation IDs, structured logs, OpenTelemetry HTTP/Kafka propagation, metrics, health, and sanitized failure records.
- Unit, integration, contract, architecture, concurrency, crash-window, security, and end-to-end tests.
- A laptop-sized Docker Compose environment for PostgreSQL, Kafka, Valkey, Keycloak, OpenTelemetry Collector, Prometheus, Grafana, Tempo, and Loki, controlled by repository scripts.

### Non-goals

- Real payment credentials/provider, PCI scope, notification delivery, inventory, line items, tax, discounts, refunds, voids, disputes, partial/multiple capture, FX, settlement, or payouts.
- A browser/mobile/operator UI or identity-provider implementation.
- Microservices, distributed transactions, Kafka exactly-once claims, event sourcing, Debezium, application use of Redis/Valkey, H2, JPA/Hibernate, or additional application datastores. The local Valkey service does not authorize application integration.
- Production cloud/IaC, backup/restore, disaster recovery, or a final data-retention policy.
- OpenAPI-generated server code. The MVP validates the contract and tests implementation conformance without generation.

Any non-goal requires a separate proposed milestone and, where significant, an ADR.

## Interfaces and data

### Local development infrastructure

`compose.yaml` is the source of truth for local dependencies. `.env.example` contains local-only placeholder defaults and may be overridden by an ignored `.env`. The environment exposes only the documented developer ports and stores durable local state in named volumes for PostgreSQL, Kafka, Prometheus, Grafana, Tempo, and Loki. Valkey is intentionally ephemeral; Keycloak persists in its own PostgreSQL database and never uses embedded H2.

The `scripts/dev-up`, `scripts/dev-down`, `scripts/dev-reset`, and `scripts/dev-status` commands validate and operate the environment. They do not build or start LedgerFlow itself.

### HTTP interfaces

The active operations in `application/src/main/openapi/ledgerflow.yaml` define:

- `POST /api/v1/orders` — required order-write scope and `Idempotency-Key`; returns an original or replayed `201`, or a documented problem response.
- `GET /api/v1/orders/{orderId}` — returns current owner-visible order state.

The following remain future operations and are not present in the active OpenAPI contract:

- `GET /api/v1/operator/operations` — paginated/filterable sanitized failure list.
- `GET /api/v1/operator/operations/{operationId}` — sanitized failure and retry history.
- `POST /api/v1/operator/operations/{operationId}/retries` — required operator-retry scope, idempotency key, and audit reason; returns `202`.

Error responses use RFC 9457 `application/problem+json`. Exact headers, payloads, status codes, scopes, and stable error codes are fixed in `docs/api-design.md`.

The implemented `application/src/testFixtures/openapi/mock-payment-provider.yaml` defines the authorize, capture, and operation-lookup support contract. It is validated by the same build but is never mounted as a LedgerFlow public route or packaged into the main artifact.

### Package interfaces

- The current `orders.internal` packages own create/read commands, domain rules, HTTP, and JDBC persistence. No cross-module order API is exposed because no other module calls it yet.
- A future `orders.api` package will own only cross-module commands and guarded order transitions that are actually needed.
- `payments.api` currently exposes the narrow locked accounting view and state transition needed by ledger; public/provider workflow APIs remain module-internal until an approved coordinator needs them.
- `ledger.api` exposes idempotent payment-capture posting and exact compensating correction operations.
- `messaging.api` exposes `appendOutboxEvent` joined to the caller's transaction.
- `notifications.api` exposes idempotent event processing to the Kafka adapter.
- `operations.api` accepts sanitized failure facts, resolution facts, and retry commands.

Cross-module callers depend only on these APIs. Payment domain and provider ports do not depend on Spring HTTP, JDBC, or Kafka adapters.

### Persistence

Flyway migrations create the tables, checks, indexes, functions, triggers, and seed accounts specified by `docs/data-model.md`. The first three are implemented; later files are planned:

1. `V001__create_orders_and_idempotency.sql`
2. `V002__create_payment_tables.sql`
3. `V003__create_immutable_ledger.sql`
4. `V004__create_operations_tables.sql`
5. `V005__create_transactional_outbox.sql`
6. `V006__create_notification_inbox_and_dlt_tables.sql`

Once merged, these migrations are immutable. Corrections use later versions.

The successful capture-finalization transaction must update payment/order and insert one balanced ledger transaction, entries, and logical outbox event atomically. It completes the order idempotency snapshot with `201` only when the original POST remains in progress; an already completed `202` snapshot is preserved. Provider calls and Kafka calls never run inside this transaction.

### Kafka interfaces

- Main topic: `ledgerflow.payment-captured.v1`
- Retry topics: `.retry-1`, `.retry-2`, `.retry-3`
- Dead-letter topic: `.dlt`
- Consumer group: `ledgerflow-notifications-v1`
- Record key: order ID
- Event type/version: `com.ledgerflow.payment.captured`, version `1`
- Attempts: one initial processing attempt plus retries after 1 second, 5 seconds, and 30 seconds, then DLT

The event envelope, safe data, headers, deduplication behavior, and DLT catalog are fixed in `docs/data-model.md`.

### Configuration

Configuration is typed and validated at startup. Production-required settings include:

- PostgreSQL migration-owner URL/user/password plus separate non-owner runtime URL/user/password and connection-pool bounds;
- Kafka bootstrap servers, security protocol, credentials, topic names, consumer group, producer timeouts, and outbox polling/lease settings;
- OAuth issuer/JWKS URI, exact audience, RS256-only policy, retrieval/cache timeouts, and readiness behavior;
- payment-provider base URI, TLS policy, connect/read timeouts, and retry limits;
- OTLP endpoint, service name, sampling, and bounded exporter queue; and
- application limits for body size, concurrency, batches, leases, and retention warnings.

Secrets come only from environment/secret injection. Local/test defaults contain placeholders and isolated container endpoints.

## Milestones

Only the current milestone is approved or in progress. Later milestones remain `Proposed`; completing a milestone does not approve the next.

### Milestone 1 — Scaffold the verified repository and application

- Status: Complete
- Intended outcome: A minimal runnable Spring Boot 4.1/Java 25 modular-monolith foundation with every governance verification task working, but no business flow implemented.
- Implementation work:
  - Add the Gradle 9.6.1 Wrapper, a multi-project Kotlin DSL build, Java toolchain 25, base package `com.ledgerflow`, one deployable `application` project, and six feature projects organized package-by-feature.
  - Use Spring Boot's BOM for managed versions and the Spring Modulith and Testcontainers BOMs for their dependency families. Include the approved Web MVC, validation, Actuator, Data JDBC, Flyway, PostgreSQL, Kafka, resource-server security, Micrometer, and OpenTelemetry capabilities.
  - Configure Spotless for Java, Kotlin DSL, and repository text; compiler `-Xlint:all -Werror` plus Checkstyle as the Java 25-compatible Error Prone equivalent; separate unit/integration/architecture source sets; OpenAPI validation; a documentation link/structure check; and aggregate `staticAnalysis`/`documentationCheck`/`verify` tasks.
  - Add Spring Modulith verification and complementary ArchUnit tests for module cycles and repository-wide technical-layer packages.
  - Add the Testcontainers BOM and PostgreSQL, Kafka, and Redis-compatible test modules. Prove only a minimal Spring context load against PostgreSQL in this milestone.
  - Add an empty OpenAPI skeleton, a Makefile command interface, safe baseline configuration, and README setup instructions.
  - Update architecture documentation only for bootstrap choices explicitly approved by the maintainer. Keep ADR 0002–0008 proposed because their later business decisions are not approved by this milestone.
- Validation commands:
  - `./gradlew --version`
  - `./gradlew tasks --all`
  - `./gradlew clean verify`
- Observable acceptance:
  - Wrapper uses Java 25 and no system Gradle.
  - Every required lifecycle task exists and runs.
  - The minimal application context loads against a PostgreSQL Testcontainer with Flyway enabled.
  - Architecture tests recognize all six modules, verify their Spring Modulith model, and enforce package-by-feature constraints.
  - Kafka and Redis-compatible Testcontainers support resolves from test-only configurations without introducing either as an application datastore.
  - No H2, application business endpoint, or mock production bypass exists.

### Milestone 2 — Provide local development infrastructure

- Status: Complete
- Intended outcome: One command starts a pinned, laptop-sized local dependency stack; Compose reports every service healthy and developers can discover every host port without introducing application functionality.
- Implementation work:
  - Add `compose.yaml` with PostgreSQL 18.4, Kafka 4.3.1 in single-node KRaft mode without ZooKeeper, Valkey 9.1.0, Keycloak 26.7.0, OpenTelemetry Collector Contrib 0.156.0, Prometheus 3.5.5 LTS, Grafana 13.1.0, Tempo 2.10.7, and Loki 3.7.3.
  - Add health checks, bounded CPU/memory, useful named volumes only, safe local placeholder configuration, and explicit host port variables.
  - Configure a separate PostgreSQL database/role for Keycloak so the identity service never falls back to embedded H2.
  - Add a Keycloak `ledgerflow` realm import with `customer` and `operator` realm roles but no users, tokens, or credentials.
  - Configure OTLP ingestion and routing to Tempo, Loki, and a Prometheus scrape endpoint; provision Grafana data sources for all three backends.
  - Add `.env.example`, ignore `.env`, add `scripts/dev-up`, `scripts/dev-down`, `scripts/dev-reset`, and `scripts/dev-status`, and expose them through the Makefile.
  - Document commands, destructive reset behavior, resource expectations, local-only security limitations, and every exposed port.
- Validation commands:
  - `docker compose --env-file .env.example -f compose.yaml config --quiet`
  - `scripts/dev-up`
  - `scripts/dev-status`
  - targeted PostgreSQL, Kafka, Valkey, Keycloak, OpenTelemetry, Prometheus, Grafana, Tempo, and Loki readiness checks documented in `README.md`
  - `./gradlew clean verify`
- Observable acceptance:
  - `docker compose ps` reports all nine services running and healthy.
  - Kafka reports a broker through its KRaft listener and no ZooKeeper service or configuration exists.
  - PostgreSQL accepts the LedgerFlow database connection and Keycloak uses a separate PostgreSQL database.
  - Valkey responds to `PING`; Keycloak publishes the `ledgerflow` realm metadata with customer/operator roles imported.
  - OpenTelemetry Collector validates and exposes healthy OTLP receivers; Prometheus, Grafana, Tempo, and Loki readiness endpoints respond successfully.
  - Grafana provisions Prometheus, Tempo, and Loki data sources without manual UI setup.
  - No actual secret, application behavior, Flyway migration, API operation, producer, or consumer is added.

### Milestone 3 — Deliver the Create Order vertical slice

- Status: Complete
- Intended outcome: Authenticated clients can create one INR order through the public API, safely replay the original result, detect changed-payload key reuse, and read the current owned order without invoking payment or Kafka behavior.
- Implementation work:
  - Complete the active create/read order operations and common RFC 9457-style problem schemas in OpenAPI before implementation.
  - Add JWT resource-server validation, order scopes, subject ownership, correlation filter, and structured HTTP logs.
  - Apply `V001` for orders and idempotency records only. Generate order IDs with PostgreSQL 18 `uuidv7()`, persist `Instant` values as `timestamptz`, store money in positive minor units plus an `INR` currency field, and reserve an optimistic version column for later guarded transitions.
  - Implement fixed-field canonical request fingerprints, SHA-256 key hashes, immutable original-response snapshots, current GET representations, and database-enforced uniqueness.
  - Claim the idempotency key, insert the `CREATED` order, and complete the response snapshot in one short PostgreSQL transaction. A conflicting insert waits on the unique key; same-hash callers replay after the winner commits, while different hashes return `409`.
  - Add focused domain unit tests plus PostgreSQL repository/concurrency and secured HTTP integration tests. Do not create payment tables, provider adapters, Kafka code, ledger rows, or outbox behavior.
- Validation commands:
  - `./gradlew openApiValidate test`
  - `./gradlew :application:integrationTest --tests '*OrderIdempotencyIntegrationTest'`
  - `./gradlew :application:integrationTest --tests '*OrderRepositoryIntegrationTest'`
  - `./gradlew :application:integrationTest --tests '*OrderHttpIntegrationTest'`
  - `./gradlew clean verify`
- Observable acceptance:
  - Missing/malformed key fails before business effects.
  - A valid authenticated request returns `201 CREATED`, a UUIDv7 order ID, positive INR money, UTC timestamps, `Location`, and a correlation ID.
  - Command re-execution returns the original result with `Idempotency-Replayed: true`; JSON property order does not change the fingerprint.
  - Changed payload returns `409`; concurrent requests create one order.
  - One subject cannot read another subject's order.
  - The database rejects duplicate idempotency scope/key claims, non-positive money, non-INR currency, invalid state, and invalid response snapshot state.
  - No payment, ledger, outbox, Kafka, or notification effect exists.

### Milestone 4 — Implement the external mock provider and payment state machine

- Status: Complete
- Intended outcome: Normal, latency, authorization-decline, temporary-failure, timeout/unknown, and invalid-response scenarios verify the provider adapter and payment state machine through a non-public integration harness without a database transaction across HTTP.
- Implementation work:
  - Implement the provider port, JDK HTTP adapter, strict response validation, typed result classification, independently stable authorization/capture request IDs, timeout lookup, and a maximum of one automatic temporary-failure retry using exponential backoff with jitter.
  - Add and validate `application/src/testFixtures/openapi/mock-payment-provider.yaml`, then implement a local/test-only JDK HTTP fixture against it; it supports deterministic success, decline, timeout-after-processing, temporary-error, and slow-success scenarios plus provider-side idempotency and lookup.
  - Implement every allowed/forbidden payment transition with optimistic version checks and immutable attempt-history events. Persist `CAPTURE_CONFIRMED` after provider success; later ledger accounting and final order/outbox work must remain separate approved local boundaries.
  - Apply `V002` for payment and attempt-history tables only. Failed-operation projections and operator APIs remain Milestone 7 work.
  - Persist the stable request ID and active state before provider I/O, run all HTTP calls and backoff without a database transaction, and persist classified results afterward.
  - Recover `AUTHORIZING`, `CAPTURING`, and explicit unknown states by provider lookup. Resend with the same request ID only after lookup proves `NOT_FOUND`; never resend after confirmed success or decline.
  - Add a payment recovery runbook and update the domain, data, threat, architecture, and ADR documentation for the implemented boundary.
  - Do not connect the active order routes to provider behavior until a later milestone explicitly approves that integration. Provider capture is invoked only by tests until Milestone 5 can finalize accounting/outbox atomically.
- Validation commands:
  - `./gradlew :modules:payments:test --tests '*PaymentStateMachineTest'`
  - `./gradlew :application:integrationTest --tests '*PaymentProviderIntegrationTest'`
  - `./gradlew :application:integrationTest --tests '*PaymentRecoveryIntegrationTest'`
  - `./gradlew :application:integrationTest --tests '*PaymentConcurrencyIntegrationTest'`
  - `./gradlew clean verify`
- Observable acceptance:
  - All provider scenarios match API/domain state and retry semantics.
  - Unknown outcomes query the same provider key before resend.
  - Declines never retry and never create financial/event effects.
  - A temporary failure performs no more than one automatic retry with bounded jittered backoff; decline and unknown outcomes perform no blind retry.
  - A malformed or contradictory response produces terminal domain failure and safe evidence for the future replayable `502` contract.
  - Captured provider outcome followed by local process failure converges through lookup to `CAPTURE_CONFIRMED`, ready for the next milestone's local financial finalization.
  - No production/public route can capture funds without ledger/outbox finalization.

### Milestone 5A — Post confirmed captures to an immutable balanced ledger

- Status: Complete
- Intended outcome: A provider-confirmed payment can be posted exactly once to an append-only balanced journal. The journal rows and an interim payment state `CAPTURE_ACCOUNTED` commit together without exposing final order/outbox behavior.
- Implementation work:
  - Apply `V003` for ledger accounts, transactions, entries, payment `CAPTURE_ACCOUNTED`, immutable-row triggers, seeded `PAYMENT_CLEARING`/`MERCHANT_PAYABLE` INR accounts, and deferred aggregate validation.
  - Add a framework-independent ledger domain model that requires two or more positive integer-minor-unit entries and equal debit/credit totals in one currency.
  - Expose a small `ledger.api` posting/compensation interface and a `payments.api` accounting boundary. The ledger implementation may depend only on the payment API, never payment internals or tables.
  - In one explicit `READ_COMMITTED` transaction, lock the payment row, verify `CAPTURE_CONFIRMED`, insert the journal transaction and entries, and transition the payment to `CAPTURE_ACCOUNTED`. Deferred database validation runs before commit.
  - Make payment-capture posting idempotent through unique payment/source keys and the payment-row lock. Repeated and concurrent requests return the existing matching posting.
  - Reject update/delete of posted transactions and entries. Corrections create a linked transaction whose entries reverse the original; no API permits mutation.
  - Store correlation ID, actor, stable source identity, description, UTC timestamp, payment/order links, and optional reversal link as audit metadata.
  - Add domain, PostgreSQL constraint, duplicate, compensation, transaction-rollback, and concurrency tests plus read-only SQL for balances and history.
  - Update ADR 0005, architecture, domain/data model, threat model, README, runbook, and this plan. Do not change public OpenAPI because no route is added.
- Validation commands:
  - `./gradlew :modules:ledger:test --tests '*JournalPostingTest'`
  - `./gradlew :application:integrationTest --tests '*LedgerPostingIntegrationTest'`
  - `./gradlew :application:integrationTest --tests '*LedgerConstraintIntegrationTest'`
  - `./gradlew :application:integrationTest --tests '*LedgerConcurrencyIntegrationTest'`
  - `./gradlew architectureTest documentationCheck`
  - `./gradlew clean verify`
- Observable acceptance:
  - Domain and PostgreSQL reject fewer than two entries, non-positive money, mixed currency, and unequal debit/credit totals.
  - A capture posting uses one clearing debit and one merchant-payable credit for the payment amount in INR.
  - Payment `CAPTURE_CONFIRMED -> CAPTURE_ACCOUNTED` and all journal rows commit or roll back together.
  - Sequential and concurrent duplicate posting creates one journal transaction and two entries and returns the same transaction ID.
  - Posted transaction/entry update and delete fail; a correction succeeds only as a new balanced reversal linked to the original.
  - SQL examples show trial/account balances and chronological payment journal history.
  - No order, outbox, Kafka, notification, operator, or public API behavior is introduced.

### Milestone 5B — Finalize order state and transactional outbox

- Status: Proposed
- Intended outcome: An accounted capture transitions to final `CAPTURED`, completes its order, persists the replayable HTTP outcome, and writes one logical outbox event in one PostgreSQL transaction.
- Implementation work:
  - Apply the outbox migration, including unique logical event keys and owner-guarded lease fields.
  - Require an existing valid `CAPTURE_ACCOUNTED` journal before finalization; transition payment/order and insert outbox atomically without reposting the ledger.
  - Persist safe event data, correlation ID, and W3C trace context with event type/version.
  - Extend the active order route only after every finalization component exists.
- Validation commands:
  - `./gradlew integrationTest --tests '*CaptureFinalizationIntegrationTest'`
  - `./gradlew integrationTest --tests '*CaptureCrashRecoveryIntegrationTest'`
  - `./gradlew integrationTest --tests '*OrderApiIntegrationTest'`
  - `./gradlew clean verify`
- Observable acceptance:
  - Accounted payment, order completion, idempotency response, and outbox converge without another provider or ledger effect.
  - Concurrent/repeated finalizers create one final state and one logical event.
  - Public responses and replay behavior match OpenAPI.

### Milestone 6 — Publish, consume, retry, dead-letter, and notify

- Status: Proposed
- Intended outcome: The committed event reaches Kafka at least once and creates exactly one notification despite duplicates; failures follow bounded retry topics and become cataloged DLT operations.
- Implementation work:
  - Apply `V006` for inbox, notifications, DLT catalog, and the DLT linkage added to existing operation records.
  - Implement leased polling with `SKIP LOCKED`, owner tokens, safe lease duration/renewal, broker acknowledgement, the specified initial-plus-nine 1/2/4/8/16/32/64/128/256-second jittered retry cycle, atomic failure projection, and operator-visible exhaustion/reset semantics.
  - Implement the versioned Kafka envelope and OpenTelemetry/correlation headers.
  - Implement record consumer, same-ID/same-hash no-op, same-ID/different-hash integrity failure, atomic inbox/notification, and post-commit offset behavior.
  - Configure one initial attempt plus three non-blocking retries at 1/5/30 seconds, direct DLT for non-retryable input, acknowledged DLT forwarding, and idempotent DLT cataloging.
- Validation commands:
  - `./gradlew integrationTest --tests '*OutboxPublisherIntegrationTest'`
  - `./gradlew integrationTest --tests '*NotificationConsumerIntegrationTest'`
  - `./gradlew integrationTest --tests '*KafkaRetryAndDltIntegrationTest'`
  - `./gradlew clean verify`
- Observable acceptance:
  - Kafka outage leaves a durable due/failed outbox row and never marks it published without acknowledgement.
  - Crash after acknowledgement can publish the same event ID twice while producing one notification.
  - Consumer crash after database commit but before offset commit produces one notification after redelivery.
  - Transient processing receives exactly four total attempts; poison/version/hash-conflict events reach the DLT appropriately.
  - Failed DLT publication leaves the source offset uncommitted.
  - Malformed records catalog safe hash/size/coordinates without raw bytes and are not replayable.
  - DLT-catalog database failure commits no offset, retries with alerting, and creates no recursive DLT.

### Milestone 7 — Add secured operator recovery and end-to-end observability

- Status: Proposed
- Intended outcome: Authorized operators can safely inspect and retry payment, outbox, and DLT failures, and a trace/correlation chain explains every synchronous and asynchronous attempt.
- Implementation work:
  - Complete operator OpenAPI schemas, read/retry scopes, pagination, safe projections, and internal-ingress configuration guidance.
  - Implement one-active-retry constraints, immutable `202` command replay, leased multi-instance worker claiming/takeover, stale-worker rejection, append-only audit, server-controlled dispatch, and operation-specific resolution evidence.
  - Implement payment resume with original provider key, outbox cycle reset with cumulative attempts retained, and replayable DLT publication with original ID/key/body, stripped retry headers, new retry correlation/trace, and retry-request causation.
  - Configure Spring Boot Actuator, Micrometer/OpenTelemetry tracing, OTLP export, HTTP/Kafka instrumentation, span links for manual retry, structured JSON logs, redaction, and safe metric dimensions.
  - Enforce secure defaults: mock code is a separate fixture enabled only for explicit local/test/demo execution, no authentication bypass exists, production requires a real provider/JWT trust, and initial JWKS load gates readiness.
- Validation commands:
  - `./gradlew integrationTest --tests '*OperatorApiIntegrationTest'`
  - `./gradlew integrationTest --tests '*OperatorRetryIntegrationTest'`
  - `./gradlew integrationTest --tests '*TracePropagationIntegrationTest'`
  - `./gradlew integrationTest --tests '*SensitiveTelemetryIntegrationTest'`
  - `./gradlew clean verify`
- Observable acceptance:
  - Customer tokens cannot access operator routes; operator read and retry scopes are distinct.
  - Concurrent or repeated retry commands schedule one server-selected action and create immutable audit evidence.
  - Two instances claim one retry once; expired takeover succeeds and stale completion is rejected.
  - Payment/outbox/notification recovery remains idempotent and resolves the failed operation on success or confirmed duplicate.
  - HTTP server/client, provider, outbox publisher, Kafka producer/consumer, database, and operator-retry spans are connected or explicitly linked.
  - Seeded secret markers never appear in logs, traces, event headers, DLT metadata, or API responses.

### Milestone 8 — Prove the complete MVP and operational failure matrix

- Status: Proposed
- Intended outcome: One reproducible demonstration and automated suite proves all product acceptance criteria and leaves implementation/operations documentation accurate.
- Implementation work:
  - Add an end-to-end test and documented local demo for success, replay, conflict, decline, timeout/recovery, Kafka duplicate, poison/DLT, and operator retry.
  - Add concurrency, failure-injection, broker/provider/database outage, telemetry-outage, and recovery coverage.
  - Add dashboards/metric names and a runbook for stale idempotency leases, payment unknown states, outbox lag/failure, consumer lag/DLT, and operator retry.
  - Update architecture, API, data, threat, and development documentation to the delivered behavior and capture any changed decision in a new ADR.
  - Review production dependency inventory, licenses, vulnerability results, secret scan, configuration defaults, and absence of H2/real credentials.
- Validation commands:
  - `./gradlew integrationTest --tests '*LedgerFlowEndToEndTest'`
  - `./gradlew clean verify`
- Observable acceptance:
  - Every AC-001 through AC-016 in `docs/product-requirements.md` has automated or explicitly recorded manual evidence.
  - After any safe retries/duplicates, one successful order has one captured payment/provider capture, one balanced immutable ledger transaction, one logical outbox event, and one notification.
  - All failure modes below are inspectable, bounded, and recoverable as documented.
  - Final documentation and OpenAPI match the running application.

## Implementation approach

### Build and dependency choices

Use Spring Boot-managed dependency versions wherever possible. Pin plugins and container images in the build/configuration; do not use dynamic versions.

Production dependencies and necessity:

| Capability | Dependency family | Why necessary |
| --- | --- | --- |
| HTTP/JSON and validation | Spring Boot web and validation starters | Public/provider APIs, RFC 9457 integration, typed validation |
| Explicit PostgreSQL access | Spring JDBC, PostgreSQL driver | Transactions, guarded SQL, triggers, `SKIP LOCKED`; no ORM added |
| Schema migration | Flyway core and PostgreSQL support | Required append-only production migrations |
| Kafka | Spring for Apache Kafka | Producer, record listener, retry/DLT integration, Boot configuration |
| Authentication | Spring Security OAuth2 resource server/Jose | JWT validation, ownership identity, operator scopes |
| Operations | Spring Boot Actuator | Health, readiness, metrics integration |
| Tracing | Micrometer tracing OpenTelemetry bridge and OTLP exporter | Required OpenTelemetry propagation/export through Spring facilities |
| Metrics export | Micrometer Prometheus registry | Expose production-standard metrics through Actuator without a custom metrics backend adapter |

The JDK does not provide Spring MVC integration, bean validation, JDBC pooling and transaction configuration, append-only schema migration, Kafka protocol integration, JWT resource-server validation, production health endpoints, or vendor-neutral telemetry export. The Spring Boot starters supply those capabilities as one tested platform and are preferable to hand-assembling their transitive libraries. Their operational and security implications are controlled as follows:

- the web and validation stack introduces an embedded server and JSON/input-processing surface, so later API milestones must add request limits, contract tests, authorization, and supported-version patching;
- JDBC, PostgreSQL, and Flyway introduce database credentials, connections, and migration authority, so configuration is externalized, tests use disposable PostgreSQL, and deployment must separate migration-owner and runtime roles before production;
- Kafka introduces background network clients and future broker credentials, so this milestone adds dependencies only—no topic, producer, consumer, or connection-on-startup behavior;
- the resource-server starter changes the security surface and will require an explicitly approved issuer, audience, algorithms, scopes, and failure behavior before business routes exist;
- Actuator exposes only health, info, and Prometheus endpoints in the scaffold; production network policy and authorization must restrict any later sensitive endpoint; and
- Micrometer/OpenTelemetry can create outbound traffic and high-cardinality or sensitive telemetry, so exporters are disabled in the context test and later milestones must configure bounded export, redaction, safe dimensions, and collector trust.

Test/build-only dependencies:

- the Testcontainers BOM plus PostgreSQL, Kafka, and Redis-compatible modules for real integration boundaries without version drift;
- Spring Modulith and ArchUnit for complementary module verification and repository architecture rules;
- a compatible OpenAPI validation Gradle plugin without server generation;
- Spotless and Checkstyle for formatting/static analysis; and
- Spring Security/MockMvc, Awaitility where polling is unavoidable, and in-memory OpenTelemetry export for deterministic tests.

Do not add Lombok, an ORM, MapStruct, Redis, Debezium, embedded Kafka, H2, a money library, or a resilience library. Payment retry classification/state is explicit domain behavior rather than a generic retry annotation.

### Implementation sequence and invariants

Implement contract and constraints before happy-path orchestration. At each boundary:

1. Persist intent/state before external work.
2. Perform HTTP/Kafka work outside a business database transaction.
3. Classify the outcome rather than catching every exception as retryable.
4. Commit the smallest complete local invariant.
5. Use stable business keys, expected-state/version updates, unique constraints, and inbox/outbox identity so recovery can safely repeat.

Use PostgreSQL 18 `uuidv7()` for order IDs because the approved database supports timestamp-ordered UUID generation without another dependency. Other future identifier strategies remain deferred. Inject `Clock` where application-generated timestamps require deterministic unit tests.

Do not log full domain objects. Define safe structured fields at each adapter. Store stable error codes and bounded summaries. Protected structured server error logs may include redacted internal exception stacks under restricted access/retention; APIs, Kafka/DLT, operator projections, and span attributes/events may not.

### OpenAPI approach

Maintain `application/src/main/openapi/ledgerflow.yaml` and `application/src/testFixtures/openapi/mock-payment-provider.yaml` as the respective public and support-service sources of truth. `openApiValidate` validates both. Contract integration tests exercise every operation/status/media/schema/header. Do not generate server interfaces/models in the MVP; this avoids generated/framework coupling while preserving contract-first review.

### Local runtime

Provide documented explicit local/test/demo configuration with PostgreSQL 18.4 and Kafka 4.3.1 containers and the separate test-fixture mock provider on a fixed local port. The main artifact excludes mock controls; production rejects mock configuration and requires a future approved real-provider URL/credentials. Local secrets are placeholders or generated ephemeral values and are ignored by Git.

## Failure modes

| Failure | Required behavior and evidence |
| --- | --- |
| Missing/invalid auth, key, payload | Reject before business rows; safe problem response |
| Concurrent same-key order | One owner claim/workflow; replay or bounded in-progress conflict |
| Process dies with idempotency in progress | Lease expires; resumer uses existing order/payment |
| Provider latency | Bounded client timeout; no open DB transaction |
| Provider decline | Terminal decline; no retry, ledger, or outbox |
| Provider temporary failure | One automatic retry; exhaustion becomes operator-visible pending state |
| Provider timeout or process loss after send | Persist/retain unknown state; lookup same operation key before resend |
| Malformed/contradictory provider response | Durable order/payment `FAILED`; cached `502` and Location; safe non-retryable operation; no ledger/outbox |
| Provider capture succeeds; local finalization fails | Local transaction rolls back; lookup confirms capture; repeat idempotent finalization |
| Illegal/stale transition | Guarded update affects zero rows and becomes conflict/integrity evidence, not a blind overwrite |
| Unbalanced/incomplete ledger | Deferred constraint aborts entire finalization transaction |
| Kafka unavailable | Outbox remains durable and retries; exhaustion is operator-visible |
| Broker ack then publisher crash | Lease expiry republishes same event; consumer deduplicates |
| Two publisher instances | `SKIP LOCKED`, owner tokens, and leases prevent simultaneous ownership; stale owners cannot mark rows |
| Consumer DB failure before commit | Offset not committed; retry handles record again |
| Consumer DB commit then offset failure | Redelivery finds same event ID/hash and performs no duplicate notification |
| Event ID with changed payload | Integrity failure; no domain effect; direct DLT |
| Transient consumer failure | Initial plus three retry-topic attempts, then DLT |
| DLT publication failure | Source offset remains uncommitted and record is redelivered |
| DLT catalog duplication | Original topic/partition/offset unique constraint makes cataloging idempotent |
| Concurrent operator retries | One active retry and one command result; all others replay/conflict |
| Retry worker crash/lease expiry | One owner/version-guarded takeover; stale worker cannot execute or complete |
| DLT catalog database outage | DLT offset remains uncommitted; bounded pause/retry and alert; no recursive DLT |
| Telemetry backend unavailable | Bounded exporter drops/retries telemetry without changing business outcome |
| Invalid correlation/trace header | Safe correlation replacement/new trace; untrusted value is never logged verbatim |

## Validation and acceptance

### Required environment

- JDK 25 selected by the Gradle toolchain.
- Docker-compatible runtime available to Testcontainers.
- Sufficient local resources for PostgreSQL 18 and Kafka 4.3.1 containers.
- No externally installed Gradle, database, broker, or identity provider is required for automated tests.

### Automated strategy

- **Unit/parameterized tests:** every allowed/forbidden state transition; money bounds/overflow; request normalization; provider result classification; retry eligibility; safe event serialization.
- **Property tests where useful:** sequences of state commands never escape the transition graph; generated debit/credit postings remain balanced or fail validation.
- **PostgreSQL integration tests:** Flyway from empty with migration owner; runtime-role DDL/immutability denial; all checks/FKs/unique/partial indexes; idempotency races/lease ownership/stale completion; optimistic-state races; parent/entry balance triggers; ledger/account immutability; atomic finalization; outbox claiming/lease/reset; inbox hash conflicts; retry-worker lease/takeover.
- **HTTP/provider tests:** both OpenAPI contracts; public status/header/schema cases including replayable `502`; JWT scope/ownership/RS256/rotation/JWKS outage; all mock scenarios; timeout-after-provider-effect; same-key provider semantics; payment-reference clearing; response redaction.
- **Kafka integration tests:** key/envelope/headers; broker outage; ack/status crash duplicate; concurrent duplicates; consumer commit/offset crash; direct/exhausted/malformed DLT; failed DLT send; catalog DB outage/redelivery; sanitized catalog; replay header reset/correlation/resolution.
- **Observability tests:** in-memory span exporter and captured structured logs prove propagation/linking and absence of seeded secrets.
- **Architecture/static tests:** feature boundaries/cycles; payment domain isolation; no H2, floating-point money, global technical layers, or forbidden dependencies.
- **End-to-end test:** one containerized flow from HTTP through notification, plus replay/conflict and operator recovery.

### Completion commands

Run focused milestone commands listed above, then from a clean build for every milestone:

```text
./gradlew --version
./gradlew clean verify
```

`verify` must execute formatting, static analysis, unit tests, PostgreSQL/Kafka integration tests, architecture tests, OpenAPI validation, and documentation checks without skipped required tests. Record command output and AC evidence in this plan's progress section before completing a milestone.

### Final acceptance invariant

After any number of safe HTTP retries, provider reconciliations, publisher duplicates, consumer redeliveries, or operator retry replays, one successful order has:

- exactly one order and payment;
- exactly one provider authorization and capture for their stable operation keys;
- payment `CAPTURED` and order `COMPLETED`;
- exactly one immutable ledger transaction with equal positive debit and credit in INR;
- exactly one logical outbox event, possibly published more than once; and
- exactly one notification for that event ID.

## Rollback and recovery

- Before a milestone starts, keep later migrations and contracts out of scope. Roll back application changes through normal version control; never rewrite a merged migration.
- Initial additive migrations are forward compatible within their milestone. Any schema correction after merge is a new Flyway migration.
- Do not automatically roll back or delete captured/ledger data. Resolve inconsistent local state through provider lookup and idempotent forward finalization; financial corrections require new ledger records.
- A failed deployment may return to the previous application only if the schema and event contract remain backward compatible. Otherwise deploy a forward fix.
- Outbox and inbox records are durable recovery evidence. Do not purge or manually mark them to recover an incident.
- Operator recovery uses the secured API, stable provider/event identity, current-state guards, and audit. Direct database/Kafka manipulation is emergency-only and requires a separately approved runbook.
- If Kafka topics or OpenTelemetry configuration are wrong, stop/reconfigure the affected adapters while leaving PostgreSQL business/outbox data intact.

## Progress

- [x] `2026-07-11 19:46Z` — Inspected the clean documentation-only repository and all governance/architecture instructions.
- [x] `2026-07-11 19:46Z` — Drafted product, domain, API, data, threat, proposed ADR, and ExecPlan documents; no application code or build files created.
- [x] `2026-07-13 08:21Z` — Recorded the maintainer's explicit approval of Milestone 1 as repository and application scaffolding, requiring a multi-project Gradle build and no business behavior; ADRs 0002–0008 remain proposed.
- [x] `2026-07-13 08:21Z` — Created the Java 25/Spring Boot 4.1 multi-project foundation, Gradle 9.6.1 Wrapper, six feature boundaries, command interface, OpenAPI skeleton, and README without business behavior.
- [x] `2026-07-13 08:21Z` — Ran `./gradlew clean verify`; all 45 actionable formatting, static-analysis, unit, PostgreSQL integration, architecture, OpenAPI, and documentation tasks completed successfully.
- [x] `2026-07-13 09:27Z` — Implemented the approved Milestone 2 nine-service local Compose environment, pinned images, laptop resource limits, PostgreSQL-backed Keycloak realm, observability provisioning, lifecycle scripts, Compose verification task, and port/setup documentation without application behavior.
- [x] `2026-07-13 09:27Z` — Started the environment with temporary host overrides for occupied local ports, confirmed all nine containers healthy, verified the KRaft quorum and absence of ZooKeeper, checked PostgreSQL/Valkey/Keycloak, and sent OTLP trace, log, and metric records end-to-end through Tempo, Loki, and Prometheus. Grafana reported all three data sources provisioned.
- [x] `2026-07-13 09:30Z` — Ran `./gradlew clean verify`; all 45 actionable formatting, static-analysis, unit, PostgreSQL integration, architecture, Compose, OpenAPI, and documentation tasks completed successfully.
- [x] `2026-07-13 09:42Z` — Recorded explicit maintainer approval for Milestone 3 as the active Create Order vertical slice, replacing the earlier inactive-route proposal while keeping payment and Kafka work out of scope.
- [x] `2026-07-13 09:42Z` — Inspected the clean worktree, multi-project/component-scan boundaries, existing empty OpenAPI contract, PostgreSQL/Flyway/Testcontainers setup, security dependencies, proposed order/idempotency design, and documentation conflicts around USD, UUIDv4, and payment-coupled order creation.
- [x] `2026-07-13 10:17Z` — Implemented and validated the OpenAPI-first create/read contract, V001 order/idempotency schema, UUIDv7 persistence, scoped canonical request hashing, atomic replay transaction, JWT ownership/scopes, correlation/problem handling, and structured logs without payment or Kafka behavior.
- [x] `2026-07-13 10:17Z` — Added domain unit tests and PostgreSQL Testcontainers context, repository, concurrent-idempotency, and secured HTTP integration tests; focused unit, integration, OpenAPI, and architecture checks pass. Full clean verification remains pending.
- [x] `2026-07-13 10:22Z` — Ran `./gradlew --no-daemon spotlessApply clean verify --console=plain`; all 52 actionable formatting, static-analysis, unit, PostgreSQL integration, architecture, Compose, OpenAPI, and documentation tasks completed successfully.
- [x] `2026-07-13 10:42Z` — Recorded explicit maintainer approval for Milestone 4 limited to payment authorization/capture, provider simulation, recovery, tests, threat modeling, and runbook work; later order integration, ledger, outbox, Kafka, notification, and operator milestones remain unapproved.
- [x] `2026-07-13 10:42Z` — Defined the unknown-outcome protocol before implementation: persist a stable stage-specific request ID and active state, call outside a transaction, classify timeout as unknown, lookup by the same ID before any resend, and use `CAPTURE_CONFIRMED` until financial finalization.
- [x] `2026-07-13 11:18Z` — Implemented ADR 0004's explicit payment state machine, stage-independent idempotent request IDs, guarded JDBC persistence, append-only attempt history, JDK HTTP provider adapter with explicit timeouts, bounded temporary-failure retry, deterministic external HTTP fixture, and lookup-first crash/timeout recovery without a public payment route.
- [x] `2026-07-13 11:18Z` — Added and passed state-machine/retry/configuration unit tests plus 15 PostgreSQL/provider integration tests covering success, both declines, temporary retry, timeout, slow response, invalid response, provider-success/local-persistence crash, optimistic races, constraints, and immutable history. Updated the provider contract, ADR, architecture, domain/data model, threat model, README, and recovery runbook.
- [x] `2026-07-13 11:28Z` — Ran `./gradlew --no-daemon clean verify --console=plain`; all 53 actionable formatting, static-analysis, unit, PostgreSQL integration, architecture, Compose, public/provider OpenAPI, and documentation tasks completed successfully.
- [x] `2026-07-13 11:35Z` — Recorded explicit maintainer approval for a ledger-only Milestone 5A and inspected the clean worktree, implemented payment state/persistence boundary, empty ledger feature, proposed ADR 0005, and the prior combined ledger/outbox milestone.
- [x] `2026-07-13 11:35Z` — Split the prior combined milestone so ledger posting uses interim `CAPTURE_ACCOUNTED` and remains non-public; final `CAPTURED`, order completion, idempotency response, and outbox stay proposed in Milestone 5B.
- [x] `2026-07-13 12:56Z` — Implemented the framework-independent ledger model, payment/ledger module APIs, V003 immutable journal and deferred constraints, atomic `CAPTURE_ACCOUNTED` boundary, replayable exact corrections, and focused balance/duplicate/concurrency/rollback tests without public, order, outbox, or Kafka behavior.
- [x] `2026-07-13 12:56Z` — Added read-only balance/history SQL and aligned ADRs, architecture, domain/data model, threat model, README, runbook, and architecture rules with the delivered ledger-only scope. Focused unit and PostgreSQL ledger integration suites pass; full verification remains pending.
- [x] `2026-07-13 13:18Z` — Ran `./gradlew --version` and `./gradlew clean verify --console=plain` on Java 25/Gradle 9.6.1 after fixing one test-only line-length finding; all 56 formatting, static-analysis, unit, PostgreSQL integration, architecture, Compose, OpenAPI, and documentation task actions passed.

## Surprises and discoveries

- The repository has no Gradle lifecycle, so the first application milestone must establish every required check before later implementation can satisfy the Definition of Done.
- The requested ExecPlan path conflicts with the default `.agent/plans/...` location. The maintainer's explicit path is treated as a one-plan exception; duplicating a living plan would create drift.
- The accepted architecture originally deferred the base package, concrete feature modules, and Gradle project structure. The maintainer's Milestone 1 approval selected `com.ledgerflow`, six feature projects, and one deployable application project. ADR 0003 was later accepted for Create Order and ADR 0004 for Milestone 4; the remaining business-flow ADRs stay proposed.
- PostgreSQL row `CHECK` constraints cannot enforce a balance across multiple entries. A deferred constraint trigger on both parent and entry changes is necessary to reject even an empty ledger transaction.
- Provider capture and the local database cannot be atomic. Stable provider operation keys, lookup, and idempotent local finalization are required; a database transaction held across HTTP would not solve this gap.
- Non-blocking Kafka retry topics keep failed records durable and partitions available but sacrifice ordering. One terminal event per order and event-ID idempotency make that tradeoff acceptable for the MVP.
- Spring Boot 4.1's BOM imports the Testcontainers 2.0.5 BOM, so the PostgreSQL, Kafka, and Redis-compatible test modules remain versionless without declaring a second platform dependency.
- Spring Modulith treats only a module's base package as exposed by default; an `api` subpackage must be annotated as a named interface. The ledger and payment API packages use compile-only `spring-modulith-api` annotations from the existing Modulith BOM, and the architecture test rejects non-exposed cross-module access.
- Testcontainers 2 deprecates its legacy generic PostgreSQL container package. The integration test uses `org.testcontainers.postgresql.PostgreSQLContainer` and enables Java 25 native access for JNA-backed container discovery.
- Architecture tests must live outside the `com.ledgerflow` application base package; otherwise Spring Modulith correctly interprets the test package as another application module.
- PostgreSQL 18 changed the official image's persistent mount to `/var/lib/postgresql`; using the pre-18 `/var/lib/postgresql/data` target would create unintended storage. The Compose volume uses the version-correct parent path.
- Docker Desktop was temporarily unavailable during the first check, then recovered. Existing host PostgreSQL and Valkey processes occupied ports 5432 and 6379, so runtime validation used temporary `POSTGRES_PORT=15432` and `VALKEY_PORT=16379` overrides while retaining documented defaults.
- Kafka's JVM CLI and the OpenTelemetry Collector validation command can exceed ten seconds under the configured CPU ceilings. Their health-check timeouts allow thirty seconds to prevent false failures without increasing service resource caps.
- Tempo and Loki deliberately delay readiness for fifteen seconds when an ingester becomes ready. Endpoint verification waits for actual `/ready` success rather than treating the image binary check alone as proof of backend readiness.
- A JUnit-managed static Testcontainer inherited by several test classes was stopped after the first class while Spring retained its cached context. The integration base now uses the Testcontainers singleton pattern so one PostgreSQL container lives for the complete test JVM.
- A newly used integration-test resource exposed duplicate conventional source-directory registration in the Gradle source sets. Removing the redundant declarations preserves the same directories and allows deterministic resource processing.
- PostgreSQL JDBC does not infer a SQL type for a raw Java `Instant` passed through `JdbcClient`. The payment adapter converts domain `Instant` values to UTC `OffsetDateTime` only at binding time; persisted columns remain `timestamptz` and mapped domain values remain `Instant`.
- The approved ledger-only scope cannot truthfully use final `CAPTURED`, because the accepted design also requires order/outbox finalization before that state. `CAPTURE_ACCOUNTED` records the narrower atomic payment/ledger boundary and prevents a public final-state claim.

## Decision log

- **2026-07-11 — Store this plan at the requested `docs/plans/mvp-execplan.md`.** This explicit maintainer instruction is a one-plan exception to `.agent/PLANS.md`; no duplicate plan will be created.
- **2026-07-11 — Keep this plan, ADRs, and every milestone Proposed.** Creating planning documents does not authorize application implementation or accept architectural decisions.
- **2026-07-11 — Initially propose one single-project Gradle modular monolith.** This proposal was superseded before implementation by the maintainer's explicit multi-project requirement on 2026-07-13.
- **2026-07-13 — Use one deployable `application` project and six feature-library projects under base package `com.ledgerflow`.** The maintainer explicitly required a multi-project build; Spring Modulith and ArchUnit verify logical module rules while Gradle provides compile-time project boundaries. This bootstrap choice is documented in `docs/architecture.md`; the broader proposed ADR 0002 remains unaccepted.
- **2026-07-13 — Use Checkstyle plus javac `-Xlint:all -Werror` as the Error Prone-equivalent static analysis.** This combination supports Java 25 without compiler-plugin coupling and provides deterministic style and compiler diagnostics. No ADR is required because the stable `staticAnalysis` lifecycle remains tool-independent.
- **2026-07-13 — Keep local infrastructure operational and non-normative for production architecture.** The exact container images and single-node topology support development only, do not accept proposed ADRs 0002–0008, and do not authorize application integration.
- **2026-07-13 — Use PostgreSQL for Keycloak, ephemeral Valkey, and six useful named volumes.** This honors the no-H2 rule, avoids treating the cache as an application datastore, and persists database, broker, and observability state while leaving reconstructable services stateless.
- **2026-07-13 — Bind every host port to loopback and disable Grafana's default plugin preinstallation.** Loopback limits accidental network exposure; disabling unpinned background downloads keeps startup deterministic while built-in Prometheus, Tempo, and Loki data sources are provisioned from version-controlled configuration.
- **2026-07-11 — Use Spring `JdbcClient`, not an ORM.** Explicit guarded SQL and PostgreSQL features are core to the correctness demonstration.
- **2026-07-11 — Use synchronous normal provider orchestration and asynchronous Kafka/notification.** This produces an observable completed `201` while keeping messaging eventual.
- **2026-07-11 — Keep public order routes inactive until Milestone 5.** Superseded for order-only creation by the maintainer's 2026-07-13 approval; the active route creates no payment or financial effect.
- **2026-07-11 — Use stable provider operation keys and explicit unknown states.** A timeout is not proof of failure.
- **2026-07-11 — Use `PAYMENT_CLEARING` debit and `MERCHANT_PAYABLE` credit.** This balances the demo without assuming revenue ownership.
- **2026-07-11 — Use PostgreSQL polling outbox plus Kafka inbox.** At-least-once behavior is explicit and does not require distributed transactions or Debezium.
- **2026-07-11 — Use one initial consumer attempt plus three retry-topic attempts at 1/5/30 seconds.** Then publish to DLT; non-retryable input goes directly there.
- **2026-07-11 — Use JWT resource-server security.** Operator retry is privileged; the application consumes but does not issue tokens.
- **2026-07-11 — Use UUIDv4 from the JDK.** Superseded for order IDs by the maintainer's 2026-07-13 UUIDv7 requirement and PostgreSQL 18's native `uuidv7()` support; no dependency is added.
- **2026-07-13 — Make order creation one short idempotency transaction.** Claiming the unique scoped key, inserting the order, and persisting the original response snapshot commit together. This avoids committed leases or incomplete resources before external payment work exists; ADR 0003 must reflect this accepted slice-specific boundary.
- **2026-07-13 — Restrict the MVP currency to INR.** The currency remains explicit and validated in API, domain, and database layers; multi-currency and FX remain out of scope.
- **2026-07-13 — Declare existing Spring capabilities directly in the orders feature.** The multi-project compiler requires the orders library to declare Web MVC, validation, JDBC, Jackson, and OAuth resource-server APIs it imports. These are the same Spring Boot-managed starters already present in the deployable application, so no new production library or version is introduced. JDBC adds the PostgreSQL transaction boundary; resource-server validation adds issuer/JWK/audience trust and scope enforcement; no provider, broker, cache, or persistence technology is added.
- **2026-07-13 — Reuse Spring Boot-managed JDBC/Jackson dependencies and the JDK HTTP client for payments.** The payments feature declares the already-approved Data JDBC and Jackson starters because its adapter compiles against those APIs. The JDK client supplies connect/request timeouts without a resilience or HTTP-client library. No new production dependency family or manually managed version is introduced; retry and classification remain explicit domain/application behavior.
- **2026-07-13 — Treat timeout and post-call crashes as unknown outcomes, not temporary failures.** Authorization/capture recovery always queries the provider with the persisted request ID; only `NOT_FOUND` permits a same-ID resend. This accepts ADR 0004 for the payment boundary and prevents duplicate provider effects.
- **2026-07-13 — Persist provider success as `CAPTURE_CONFIRMED`.** `CAPTURE_ACCOUNTED` now means the balanced journal also committed; final `CAPTURED` remains reserved for later order/outbox finalization.
- **2026-07-13 — Split ledger posting from final outbox activation at the maintainer's explicit scope boundary.** Milestone 5A transitions `CAPTURE_CONFIRMED -> CAPTURE_ACCOUNTED` in the same transaction as the journal. Milestone 5B remains unapproved and owns `CAPTURED`, order completion, HTTP finalization, and outbox. ADRs 0004 and 0005 record the split.
- **2026-07-13 — Use Spring `READ_COMMITTED`, payment-row locking, and uniqueness for ledger idempotency.** Same-payment writers serialize on `SELECT ... FOR UPDATE`; unique payment/source indexes are database backstops. No new production dependency is necessary because existing Spring transaction/JDBC capabilities cover the boundary. ADR 0005 records the isolation decision.
- **2026-07-13 — Export `payments.api` and `ledger.api` as Spring Modulith named interfaces.** Compile-only annotation dependencies use the already selected Modulith BOM and add no runtime library. This makes the documented API/internal distinction enforceable by the existing architecture check.
- **2026-07-11 — Validate OpenAPI without server code generation.** Contract tests enforce conformance without generated framework coupling.
- **2026-07-11 — Contract the mock provider separately and exclude it from the main artifact.** Its external HTTP behavior is validated without exposing simulator controls in production.
- **2026-07-11 — Separate Flyway owner and runtime database roles.** Migration authority does not grant the application DDL or mutation rights over immutable financial/audit data.

## Outcome and follow-up

Current outcome: Milestones 1 through 5A are complete. The repository has a verified multi-project application foundation, validated local dependency/observability environment, an OpenAPI-first secured Create Order slice, and non-public payment/provider and ledger slices. V001 proves UUIDv7 orders and durable Create Order idempotency. V002 and PostgreSQL/provider tests prove explicit legal transitions, stable provider request IDs, bounded retry, terminal declines, lookup-first crash recovery, optimistic concurrency, and immutable attempt evidence without holding a transaction across HTTP. V003 and ledger tests prove two-or-more-entry/equal-total domain rules, integer INR capture postings, payment/order links, deferred PostgreSQL balance/source validation, atomic `CAPTURE_ACCOUNTED` transition, sequential and concurrent idempotency, append-only rows, and exact compensating corrections. No outbox, Kafka producer/consumer, notification, operator API, public payment behavior, or final order/capture orchestration has been created. Milestone 5B and later milestones remain proposed and require separate approval.

When all milestones are complete, update this section with:

- delivered behavior and any approved deviations;
- `./gradlew clean verify` evidence;
- AC-001 through AC-016 evidence;
- accepted/superseded ADRs;
- production dependency inventory;
- operational limitations and residual risks; and
- separately proposed follow-ups for real provider integration, notification delivery, refunds/reversals, multi-currency, data retention, deployment, backup/recovery, and performance targets.

Follow-up work is not approved by completing this plan.
