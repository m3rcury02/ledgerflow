# Deliver the LedgerFlow MVP Financial Event Flow

## Metadata

- Status: In Progress
- Owner: Codex
- Created: 2026-07-11
- Last updated: 2026-07-15
- Approved by: LedgerFlow maintainer
- Approval date: 2026-07-13
- Current milestone: Milestone 6 (`Complete`)
- Milestone 5D approved by: LedgerFlow maintainer
- Milestone 5D approval date: 2026-07-15
- Milestone 6 approved by: LedgerFlow maintainer
- Milestone 6 approval date: 2026-07-15
- Canonical plan path: `docs/plans/mvp-execplan.md` by explicit maintainer request

## Purpose and outcome

Build a production-grade portfolio backend that visibly and safely completes this flow:

```text
POST order
  -> authorize and capture through an external HTTP mock provider
  -> atomically post balanced ledger entries and write the outbox
  -> finalize payment/order in a separate guarded local transaction
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
- accepted decisions in ADRs 0003 through 0007 and 0009 through 0013, plus proposed ADRs 0002 and 0008, under `docs/adr`

## Current state

The repository contains the verified foundation and completed Create Order, payment/provider, ledger, outbox/Kafka, notification, resilience, security, and abuse-remediation slices from Milestones 1 through 5D, plus the in-progress Milestone 6 public workflow:

- `AGENTS.md` establishes Java 25, Spring Boot 4.1, Gradle Kotlin DSL, modular-monolith, PostgreSQL, Flyway, OpenAPI, money/time, idempotency, observability, scope, and quality rules.
- `.agent/PLANS.md` defines ExecPlan structure and one-approved-milestone discipline.
- `settings.gradle.kts`, the root build, and the Gradle 9.6.1 Wrapper define one deployable `application` project and six feature-library projects under `modules`.
- `application/src/main/java/com/ledgerflow/LedgerFlowApplication.java` is the Spring Boot 4.1 entry point; `modules/orders`, `modules/payments`, and `modules/ledger` contain implemented business behavior.
- `application/src/main/openapi/ledgerflow.yaml` defines the authenticated complete-workflow create/read order contracts and RFC 9457-style problems.
- Flyway V001 through V008 create the implemented order, HTTP-idempotency, payment/attempt, ledger, outbox, notification/DLT, semantic-effect, terminal-evidence, and public-finalization schemas.
- `application/src/integrationTest` proves context loading, Flyway startup/upgrades, repository constraints, HTTP idempotency/authorization, provider recovery, payment/workflow concurrency, ledger/outbox atomicity, and Kafka/notification idempotency against PostgreSQL and Kafka Testcontainers.
- `application/src/architectureTest` verifies exactly six Spring Modulith modules and complementary ArchUnit package rules.
- `compose.yaml`, `.env.example`, `infra`, and `scripts/dev-*` provide the pinned nine-service local environment, safe placeholder defaults, provisioning, and lifecycle commands.
- `build.gradle.kts` provides the stable formatting, static-analysis, unit, integration, architecture, OpenAPI, Compose, documentation, and aggregate `verify` tasks.
- `docs/architecture.md`, `docs/development-workflow.md`, `README.md`, and this plan record the approved bootstrap choices and developer commands.
- ADR 0001 establishes the ADR process; accepted ADRs 0003 through 0007 and 0009 through 0013 record the implemented business and operational boundaries. ADRs 0002 and 0008 remain proposed.

The maintainer's 2026-07-13 transactional-outbox and Kafka request approves the revised Milestone 5B below and accepts the outbox/Kafka decision in ADR 0006. It deliberately excludes public payment/order/operator endpoints and final order-state orchestration. ADR 0007 is accepted only for the Kafka propagation implemented by this milestone; operator recovery tracing remains proposed.

The maintainer's 2026-07-14 resilience request explicitly expands the still-in-progress Milestone 5B with bounded provider circuit/bulkhead controls, distinct timeout budgets, graceful draining, Kafka backpressure, dependency health/startup validation, profile-gated fault injection, and Toxiproxy failure tests. This is one revised active milestone rather than a second concurrent plan. It does not approve public orchestration, operator HTTP APIs, new business states, or schema changes.

The maintainer's 2026-07-14 security request approves Milestone 5C below. It hardens the current order HTTP boundary, local Keycloak roles/scopes, existing privileged replay audit, request/resource limits, sensitive-data controls, and vulnerability scanning. It reserves future operator paths behind operator/admin roles and operation scopes but does not approve the operator recovery API, new privileged actions, public payment orchestration, or a new datastore.

The 2026-07-14 abuse-case review and supporting design record at `.agent/plans/2026-07-14-abuse-case-remediation.md` verified five residual gaps. This canonical plan is the sole authority for their ordering, approval, progress, and completion. The maintainer's 2026-07-15 planning instruction approves Milestone 5D only: R1 management isolation/probe bounding and R2 semantic notification idempotency/malformed-DLT progress. The supporting record supplies finding evidence and detailed design, but its R1 through R5 labels are not independent implementation milestones. Quota/retention (supporting R3) and authenticated, bounded replay (supporting R4) remain separately proposed follow-ups.

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
- Transactional outbox, Kafka publication, inbox deduplication, notification persistence, bounded consumer retries, DLT catalog, and operator replay.
- Correlation IDs, structured logs, OpenTelemetry HTTP/Kafka propagation, metrics, health, and sanitized failure records.
- Provider circuit breaking and concurrency isolation, bounded shutdown draining, Kafka consumer backpressure, startup dependency validation, and health/readiness/liveness probes.
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

- `POST /api/v1/orders` — required order-write scope, `Idempotency-Key`, INR amount, and opaque demonstration payment-method reference; returns an original or replayed `201`, a durable recoverable `202`, or a documented problem response.
- `GET /api/v1/orders/{orderId}` — returns current owner-visible order/payment state without claiming asynchronous Kafka or notification completion.

The following remain future operations and are not present in the active OpenAPI contract:

- `GET /api/v1/operator/operations` — paginated/filterable sanitized failure list.
- `GET /api/v1/operator/operations/{operationId}` — sanitized failure and retry history.
- `POST /api/v1/operator/operations/{operationId}/retries` — required operator-retry scope, idempotency key, and audit reason; returns `202`.

Error responses use RFC 9457 `application/problem+json`. Exact headers, payloads, status codes, scopes, and stable error codes are fixed in `docs/api-design.md`.

The implemented `application/src/testFixtures/openapi/mock-payment-provider.yaml` defines the authorize, capture, and operation-lookup support contract. It is validated by the same build but is never mounted as a LedgerFlow public route or packaged into the main artifact.

### Package interfaces

- The current `orders.internal` packages own the concrete public workflow coordinator, domain rules, HTTP, and JDBC persistence. `orders.api` exposes only its public create/read use case and stable public projections.
- `payments.api` exposes narrow initialization, advancement, read, accounting, and finalization operations needed by the approved orders/ledger coordinators; provider ports and state-machine internals remain private.
- `ledger.api` exposes idempotent payment-capture posting and exact compensating correction operations.
- `messaging.api` exposes typed payment-captured envelope types and `OutboxEventAppender`, which must join the caller's transaction.
- `notifications.api` exposes only the narrow audited `DeadLetterReplay` command; Kafka processing remains module-internal.
- `operations.api` accepts sanitized failure facts, resolution facts, and retry commands.

Cross-module callers depend only on these APIs. Payment domain and provider ports do not depend on Spring HTTP, JDBC, or Kafka adapters.

### Persistence

Flyway migrations create the tables, checks, indexes, functions, triggers, and seed accounts specified by `docs/data-model.md`. The first eight are implemented:

1. `V001__create_orders_and_idempotency.sql`
2. `V002__create_payment_tables.sql`
3. `V003__create_immutable_ledger.sql`
4. `V004__create_transactional_outbox.sql`
5. `V005__create_notification_inbox_and_dead_letters.sql`
6. `V006__add_notification_semantic_identity.sql`
7. `V007__catalog_terminal_dlt_records.sql`
8. `V008__finalize_public_order_workflow.sql`

Once merged, these migrations are immutable. Corrections use later versions.

The implemented capture-accounting transaction locks a provider-confirmed payment, inserts one balanced ledger transaction and its entries, transitions the payment to `CAPTURE_ACCOUNTED`, and appends one logical outbox event atomically. Provider and Kafka calls never run inside this transaction. A separate provider-free finalization transaction verifies that evidence, transitions payment/order to `CAPTURED`/`COMPLETED`, and stores the original HTTP result. Provider calls run outside PostgreSQL transactions; Kafka publication and notification remain asynchronous.

### Kafka interfaces

- Main topic: `ledgerflow.payment-captured.v1`
- Dead-letter topic: `.dlt`
- Consumer group: `ledgerflow-notifications-v1`
- Record key: order ID
- Event type/schema: `com.ledgerflow.payment.captured`, schema version `1`
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

The required order is: Milestones 1, 2, 3, 4, 5A, 5B, 5C, 5D, and 6 (`Complete`); Milestone 7A (`Proposed`); Milestone 7B (`Proposed`); and Milestone 8 (`Proposed`). R3 and R4 are separately proposed follow-ups, not inserted or implied as approved milestones in that sequence.

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
  - Apply `V002` for payment and attempt-history tables only. Failed-operation projections and operator APIs remain Milestone 7B work.
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

### Milestone 5B — Add transactional outbox, Kafka delivery, and resilience hardening

- Status: Complete
- Intended outcome: Capture accounting writes one logical versioned outbox event in the same PostgreSQL transaction as the balanced ledger/payment operation. A dedicated multi-instance-safe worker publishes it to Kafka at least once, and an idempotent, backpressured consumer creates one notification despite duplicate delivery. Provider, database, Kafka, overload, startup, and shutdown failures are bounded, observable, and recoverable without changing financial or event identity.
- Implementation work:
  - Apply `V004` for the outbox and `V005` for inbox, notifications, DLT catalog, and replay audit. Operations API tables remain future work.
  - Extend the existing `READ_COMMITTED` capture-accounting transaction with a `Propagation.MANDATORY` outbox append. Do not add order completion, final `CAPTURED`, public routes, or provider calls.
  - Define a canonical envelope containing event ID, type, schema version, aggregate ID, correlation ID, causation ID, occurred-at timestamp, and typed payment-captured data. Persist validated W3C trace context separately from the immutable envelope.
  - Implement leased polling with `FOR UPDATE SKIP LOCKED`, per-claim owner tokens, safe lease expiry, acknowledged Kafka sends, bounded exponential publisher retries, and owner-guarded completion/failure updates. Kafka I/O must not occur in a PostgreSQL transaction.
  - Implement record consumption with same-ID/same-hash no-op, same-ID/different-hash integrity failure, atomic inbox/notification insertion, and record-level offset commits.
  - Configure one initial consumer attempt plus three bounded retries, direct DLT for non-retryable input, acknowledged DLT forwarding, and idempotent DLT cataloging.
  - Add an explicit test seam for a process failure after Kafka acknowledgement but before the outbox marker. Lease expiry must republish the same event ID and the inbox must prevent a second notification.
  - Add narrow command-line DLT replay that accepts only cataloged, validated records; preserves immutable event ID/key/body; strips delivery exception headers; publishes with a new correlation/trace context; and writes append-only request/result audit. Do not add the proposed operator HTTP API.
  - Replace the provider's single response deadline with explicit connect, read/response, and overall call timeouts. Keep exponential backoff with jitter and make retry eligibility an explicit classifier: only confirmed temporary failures retry; declines, invalid responses, and unknown outcomes do not.
  - Decorate the mock-provider port with a Resilience4j count-window circuit breaker and semaphore bulkhead. Confirmed declines count as successful provider availability, while temporary/unknown/invalid transport outcomes count toward opening. Circuit-open and bulkhead-full calls fail fast with stable temporary codes.
  - Use the existing `operations` feature for a small cross-module in-flight work API, bounded graceful drain, dependency readiness, startup validation, and profile-gated fault-injection hooks. The production profile must reject enabled fault injection.
  - Bound Kafka intake with typed concurrency, `max.poll.records`, poll/fetch limits, pause-based retry backoff, and explicit listener shutdown timeouts. Outbox scheduling and the Java 25 HTTP client must drain or report timeout rather than silently abandoning work.
  - Configure Boot liveness as internal-process state only. Readiness includes database/startup state and Kafka only when a Kafka adapter is enabled. A temporary broker outage must not corrupt or discard the durable outbox.
  - Add Testcontainers Toxiproxy coverage for provider latency, connection reset, and timeout plus temporary PostgreSQL and Kafka unavailability. Fault controls are test/local only and have bounded delays and an explicit allowlist.
  - Update architecture, data/domain model, threat model, runbook, README, ADR 0006, the Kafka-applicable portion of ADR 0007, and this plan with accurate at-least-once guarantees.
- Validation commands:
  - `./gradlew :modules:messaging:test :modules:notifications:test`
  - `./gradlew :application:integrationTest --tests '*TransactionalOutboxIntegrationTest'`
  - `./gradlew :application:integrationTest --tests '*OutboxPublisherIntegrationTest'`
  - `./gradlew :application:integrationTest --tests '*KafkaRetryAndDltIntegrationTest'`
  - `./gradlew :modules:payments:test --tests '*PaymentProviderResilienceTest'`
  - `./gradlew :modules:operations:test --tests '*DrainableWorkTrackerTest'`
  - `./gradlew :application:integrationTest --tests '*ProviderToxiproxyIntegrationTest'`
  - `./gradlew :application:integrationTest --tests '*DependencyToxiproxyIntegrationTest'`
  - `./gradlew architectureTest documentationCheck`
  - `./gradlew clean verify`
- Observable acceptance:
  - A new capture journal, `CAPTURE_ACCOUNTED` transition, and one logical outbox row commit or roll back together; replay/concurrency does not create another logical event.
  - Kafka outage leaves a durable due/failed outbox row and never marks it published without acknowledgement.
  - Crash after acknowledgement can publish the same event ID twice while producing one notification.
  - Consumer crash after database commit but before offset commit produces one notification after redelivery.
  - Transient processing receives exactly four total attempts; poison/version/hash-conflict events reach the DLT appropriately.
  - Failed DLT publication leaves the source offset uncommitted.
  - Malformed records catalog safe hash/size/coordinates without raw bytes and are not replayable.
  - A validated DLT record can be replayed only through an audited command using an explicit actor and reason; malformed input cannot be replayed.
  - Event and transport headers contain the required envelope identity, correlation, causation, and propagated W3C trace context.
  - Documentation states PostgreSQL business/outbox atomicity, at-least-once publication and consumption, and one idempotent notification side effect without claiming exactly-once end-to-end delivery.
  - Connect, read, and overall provider timeouts are positive, ordered, externally configurable, and distinguish timeout/unknown outcomes from retryable connection-establishment failures.
  - A confirmed decline invokes the provider once; temporary failures stop at the configured maximum; the circuit opens after its threshold, rejects calls without invoking the provider, and closes after a successful half-open probe.
  - The provider bulkhead never exceeds its configured concurrency and rejects excess work without queue growth.
  - Kafka consumers pause during bounded retry backoff, fetch a bounded number of records, and finish or explicitly fail in-flight work within the shutdown deadline.
  - `/actuator/health/liveness` excludes external dependencies; readiness reports database/Kafka/drain state, and a separate provider-circuit health component is inspectable without leaking credentials or payloads.
  - Startup rejects an unavailable database, missing required Kafka topics, unsafe resilience settings, and fault injection outside local/test profiles; temporary Kafka unavailability is observable without deleting the outbox.
  - Toxiproxy tests restore every injected fault and prove recovery. Duplicate delivery after faults creates neither a second ledger journal nor a second notification.

### Milestone 5C — Harden authentication, authorization, input, and supply-chain controls

- Status: Complete
- Intended outcome: The existing order API fails closed with production JWT validation, explicit customer/operator/admin role mapping, owner-only reads, bounded and strictly validated requests, secure response headers, and per-instance write throttling. Existing privileged replay actions retain immutable PostgreSQL audit evidence. Local Keycloak can issue correctly scoped/audienced tokens without committed credentials, and one repeatable command scans application dependencies, repository secrets/misconfiguration, and every Compose image for actionable vulnerabilities.
- Implementation work:
  - Keep Spring Security's OAuth 2.0 JWT resource server and exact issuer, audience, RS256, expiry, and not-before validation. Add an allowlisted Keycloak `realm_access.roles` converter for `customer`, `operator`, and `admin` while retaining standard `SCOPE_` authorities.
  - Require both the matching order scope and `customer` or `admin` role on active order routes. Reserve `/api/v1/operator/**` so reads require operation-read scope plus `operator` or `admin`, and writes require operation-retry scope plus `operator` or `admin`; do not add an operator controller or business operation.
  - Keep owner authorization in the order query itself and return the same `404` for missing and differently owned orders. Expand negative tests for no token, malformed/invalid claims where practical, missing scope, missing/wrong role, operator-route privilege escalation, and cross-customer object access.
  - Add explicit CSP, referrer, permissions, frame, content-type, cache, and HTTPS-only HSTS policy. Keep CSRF disabled only because bearer-token APIs are stateless and do not use browser cookies.
  - Limit HTTP request headers and JSON document/body size; reject duplicate JSON properties, excessive nesting/tokens, unsupported media/content encodings, unexpected query parameters, unknown fields, malformed values, and oversized payloads with bounded RFC 9457 responses.
  - Rate-limit `POST /api/v1/orders` per authenticated subject, with a bounded in-memory key set, deterministic retry metadata, no raw subject in logs/metrics, and externally configurable positive limits. This application limiter is defense in depth per instance; a trusted deployment ingress remains responsible for global multi-instance and unauthenticated volumetric controls.
  - Add `admin` to the local Keycloak realm, define order/operation OAuth scopes and a `ledgerflow-api` audience mapper, and retain no users, client secrets, or real credentials in the realm import.
  - Prove `message_replay_audit` update/delete rejection and retain explicit actor/reason validation for the existing CLI replay. Add no migration because V005 already created the required immutable trigger.
  - Add a version-and-digest-pinned Trivy script that builds/scans the application artifact for dependencies, scans the repository for secrets/misconfiguration, and scans all explicit Compose images. Repository-secret and application-artifact findings fail without exceptions. Compose findings fail unless they match an explicitly approved, exact, digest-bound, expiring local-development risk record while remaining visible in scanner output; document database freshness, exception governance, production prohibition, and the scanner's Docker-socket privilege.
  - Update OpenAPI, architecture/API/threat/workflow/definition-of-done/runbook/README documentation, and this plan. Do not add cards, CVVs, PAN fields, provider credentials, operator HTTP behavior, or production secrets.
- Validation commands:
  - `./gradlew :modules:orders:test`
  - `./gradlew :application:integrationTest --tests '*OrderHttpIntegrationTest'`
  - `./gradlew :application:integrationTest --tests '*SecurityIntegrationTest'`
  - `./gradlew :application:integrationTest --tests '*KafkaRetryAndDltIntegrationTest'`
  - `./gradlew openApiValidate architectureTest documentationCheck composeValidate`
  - `scripts/security-scan`
  - `./gradlew clean verify`
- Observable acceptance:
  - Valid JWTs require exact trust claims, the route scope, and an allowlisted realm role. Missing/invalid tokens return safe `401`; insufficient role/scope returns safe `403`.
  - Customer A receives `404` for Customer B's order and cannot infer whether it exists. Operator-only role/scope combinations cannot access customer objects, and customers cannot cross the reserved operator boundary.
  - Active API success and problem responses contain the configured security headers. HSTS appears only for HTTPS requests.
  - Unknown/duplicate JSON fields, unsupported content type/encoding, unexpected query input, excessive nesting, and payloads over the configured byte/document limits are rejected before a business row is written.
  - The configured number of writes per subject succeeds or reaches ordinary validation; the next request receives `429`, `Retry-After`, correlation ID, and no business effect. Rate-limit state is bounded and contains no raw bearer token or idempotency key.
  - Local Keycloak realm JSON contains customer/operator/admin roles, order and operation scopes, and the LedgerFlow API audience without any user password or client secret.
  - Direct SQL update/delete of privileged replay audit evidence fails. Existing replay tests still show actor, reason, action, correlation, and timestamp evidence.
  - Seeded bearer/card/CVV/secret markers do not appear in structured logs, trace attributes, problem details, outbox/DLT metadata, or persisted order/idempotency data; card-like fields are rejected as unknown input.
  - The security scan reports no committed secret or fixed HIGH/CRITICAL packaged-application dependency and no unapproved, changed, stale, or expired Compose finding. Any Compose exception names the exact image digest/target/package/version/vulnerability tuple, owner, rationale, reachability, mitigation, acceptance/expiry, and re-review triggers; lasts at most 30 days; remains visible; and applies only to local development, never production.

### Milestone 5D — Close pre-observability abuse-case blockers

- Status: Complete
- Intended outcome: Management probes cannot amplify unauthenticated public work, re-enveloped Kafka records cannot repeat one notification business effect, and malformed DLT records cannot starve later records after sanitized terminal evidence is durable. This milestone contains exactly R1 followed by R2; quota/retention and authenticated replay hardening are not approved by it.
- Canonical scope: This section controls approval and completion. `.agent/plans/2026-07-14-abuse-case-remediation.md` is the supporting evidence/design record for Findings 1 through 3 and the detailed R1/R2 implementation constraints.
- R1 — management-port isolation and bounded/coalesced dependency probes:
  - Run Actuator on a separate configurable management port. Remove application-port Actuator permit rules and document mandatory ingress/network-policy isolation; application security configuration must not imply that the management port is publicly reachable.
  - Expose only status-only liveness/readiness and protected Prometheus access. Do not expose sensitive health components or details publicly.
  - Reuse one lifecycle-managed Kafka Admin or an equivalent managed client. Cache both successful and failed readiness results for a short bounded TTL and coalesce concurrent readiness computations so a request burst cannot multiply database/Kafka work. Startup validation bypasses the cache.
  - Keep liveness free of external dependency probes; retain the documented readiness semantics.
- R2 — semantic notification idempotency and bounded malformed-DLT handling:
  - Apply additive Flyway migrations `V006__add_notification_semantic_effect_identity.sql` and `V007__record_terminal_dlt_evidence.sql`; never edit V001 through V005.
  - Retain `event_id` uniqueness and payload-hash conflict detection for transport-level deduplication. Add a versioned, database-unique semantic-effect identity based on the immutable capture ledger transaction, with compared content sufficient to detect conflicting order, payment, causation, amount, currency, and capture time. Do not use only event type and payment ID.
  - Treat a matching semantic effect in a new envelope as a successful no-op; treat conflicting content for the same semantic identity as a non-replayable integrity failure. Concurrent records must converge on one notification through a database constraint.
  - Classify missing or malformed original-routing headers and invalid DLT input as terminal. Persist immutable, bounded, sanitized evidence keyed by the actual DLT topic/partition/offset, then acknowledge. If evidence persistence fails, do not commit the offset. A poison record must not starve a later valid record on the partition.
  - Add low-cardinality semantic-duplicate/conflict and terminal-DLT metrics, alerts/runbook guidance, the transport-versus-business idempotency explanation, and least-privilege Kafka ACL documentation. Do not add a quarantine topic.
- Validation commands:
  - `./gradlew :modules:operations:test`
  - `./gradlew :application:integrationTest --tests '*Management*'`
  - `./gradlew :modules:notifications:test`
  - `./gradlew :application:integrationTest --tests '*KafkaRetryAndDltIntegrationTest'`
  - `./gradlew :application:integrationTest --tests '*MigrationCompatibilityIntegrationTest'`
  - `./gradlew architectureTest documentationCheck spotlessCheck`
  - `git diff --check`
  - `./gradlew clean verify`
- Observable acceptance:
  - The application port serves no Actuator path. The configurable management port differs from it outside tests, has no public ingress contract, and exposes status-only liveness/readiness plus management-network-only Prometheus.
  - At least 100 concurrent readiness requests inside one TTL execute at most one database probe and one Kafka probe; no request creates a Kafka Admin; a cached failure expires and recovers without restart.
  - Same event ID/content remains a transport no-op and same event ID/conflicting content remains an integrity failure.
  - A new event ID with the same semantic identity/content creates a second inbox record marked semantic duplicate but no second notification. Conflicting semantic content reaches DLT without a second notification. Concurrent re-enveloping produces exactly one notification.
  - Every terminal malformed-DLT class stores one immutable sanitized row using actual DLT coordinates, commits the offset only after evidence is durable, and allows the next partition record to proceed. A temporary PostgreSQL outage retains the offset and recovers without duplicate evidence.
  - V006/V007 upgrade representative V005 data without deleting or choosing among conflicts; incompatible legacy data fails closed for explicit reconciliation.
  - Documentation accurately distinguishes at-least-once transport delivery, transport idempotency, and business-side-effect idempotency, and preserves least-privilege Kafka ACLs.
  - All focused commands and `./gradlew clean verify` pass before Milestone 5D is marked Complete.

### Milestone 6 — Finalize order and expose the complete public workflow

- Status: Complete
- Approval gate: Satisfied. Milestone 5D is Complete, and the maintainer explicitly approved Milestone 6 on 2026-07-15.
- Intended outcome: An accounted and event-recorded capture transitions to final `CAPTURED`, completes its order, and connects the existing public order workflow without repeating provider, ledger, or outbox effects.
- Implementation work:
  - Update the OpenAPI contract first so `POST /api/v1/orders` accepts only an opaque mock payment-method reference and returns truthful completed, declined, retry-pending, or provider-protocol outcomes; `GET` exposes the durable current order/payment state without implying synchronous notification delivery.
  - Put the concrete workflow coordinator in `orders`; export only the narrow `orders.api` and `payments.api` records and operations needed for cross-module initialization, advancement, current-state reads, and finalization. Do not introduce a generic workflow framework.
  - In one short initial PostgreSQL transaction, claim the scoped HTTP idempotency key, create the `PAYMENT_PROCESSING` order and its payment with a stable authorization operation ID, and attach the order identity to the in-progress record. Commit before provider I/O.
  - Drive authorization and capture outside a database transaction. Reconcile active/unknown states by provider lookup, resend only after `NOT_FOUND`, and reuse the persisted stage operation ID. Retry only classified temporary failures within existing bounds.
  - Post the capture journal and append the existing logical payment-captured outbox event through the already idempotent ledger transaction. Then use a separate short local transaction to guard `CAPTURE_ACCOUNTED -> CAPTURED`, `PAYMENT_PROCESSING -> COMPLETED`, and completion of the original HTTP response snapshot.
  - Map authorization/capture decline to `PAYMENT_DECLINED`, exhausted temporary/unknown recovery to `PAYMENT_RETRY_PENDING`, and malformed/contradictory provider responses to `FAILED` plus a replayable RFC 9457 provider-protocol response with an owner-visible order location.
  - Make repeated and concurrent resumptions converge through optimistic versions, row locks, current-state re-reads, and database uniqueness for one payment per order, one capture journal, and one logical outbox event. Kafka publication and notification remain asynchronous and are never part of the HTTP transaction.
  - Add a forward-only Flyway migration for the final order/payment states and resumable idempotency response shape. Keep merged migrations unchanged.
  - Align product, domain, API, data, threat, architecture, README, runbook, and a new accepted workflow ADR. Explicitly supersede the Create Order slice's immutable `CREATED` snapshot only for the now-approved complete public workflow.
- Validation commands:
  - `./gradlew --no-daemon openApiValidate :modules:orders:test :modules:payments:test --console=plain`
  - `./gradlew --no-daemon :application:integrationTest --tests '*PublicOrderWorkflowIntegrationTest' --tests '*CaptureFinalizationIntegrationTest' --tests '*WorkflowKafkaIntegrationTest' --console=plain`
  - `./gradlew --no-daemon :application:integrationTest --tests '*OrderHttpIntegrationTest' --tests '*JwtResourceServerSecurityIntegrationTest' --tests '*SensitiveDataSecurityIntegrationTest' --console=plain`
  - `scripts/security-scan`
  - `git diff --check`
  - `./gradlew --no-daemon clean verify --console=plain`
- Observable acceptance:
  - An authenticated successful POST returns one truthful `201` representation with order `COMPLETED` and payment `CAPTURED`; the ledger is balanced, one logical outbox event is durable, and Kafka/notification completion is not awaited or claimed.
  - Identical HTTP replay returns the original status, location, and business representation; the same key with changed canonical payload returns `409`; concurrent requests/resumptions create no duplicate order, provider mutation, ledger journal, or logical outbox event.
  - Authorization decline, capture decline, bounded temporary failure, timeout with confirmed lookup, timeout with `NOT_FOUND` and safe same-ID resend, malformed response, provider-success/local-persistence crash, and ledger/outbox/finalization crash windows produce the documented recoverable states and HTTP outcomes.
  - Kafka unavailability leaves `COMPLETED`/`CAPTURED`, the balanced journal, and durable pending outbox intact. Duplicate publication/delivery and a semantic duplicate under a new event ID still create exactly one notification effect.
  - Owner predicates continue to return `404` for cross-customer reads; missing/wrong authentication remains `401`/`403`; opaque payment references, tokens, and sensitive payloads are absent from responses and logs.
  - No provider HTTP or Kafka I/O occurs inside a PostgreSQL transaction, and no end-to-end exactly-once or synchronous-notification claim is introduced.
  - All focused commands, the security scan, and the complete clean verification pass before Milestone 6 is marked Complete.

### Milestone 7A — Add end-to-end observability

- Status: Proposed
- Approval gate: Prerequisites satisfied because Milestones 5D and 6 are Complete. Milestone 7A remains Proposed until the maintainer explicitly approves it.
- Intended outcome: Operators can follow one complete order journey from inbound HTTP through the provider, PostgreSQL business transaction, ledger, outbox, Kafka, and notification worker using correlated traces, metrics, and structured logs without exposing tokens or personal data.
- Implementation work:
  - Configure OpenTelemetry instrumentation and Micrometer metrics with OTLP export, W3C `traceparent`/`tracestate` propagation, HTTP server/client and Kafka producer/consumer instrumentation, PostgreSQL spans, and response `X-Correlation-Id` continuity.
  - Retain structured JSON production logs. Correlate logs with trace and correlation IDs while redacting bearer/security tokens, idempotency keys, payment references, personal data, request/response bodies, Kafka payloads, and SQL parameters.
  - Expose Prometheus only on Milestone 5D's protected management interface. Add bounded-cardinality business metrics for orders, payments, ledger posting, outbox backlog, dead-letter intake, and consumer lag; identifiers, subjects, coordinates, hashes, and error text must not be labels.
  - Provision Grafana dashboards and data-source links as code so metrics link to Tempo traces and trace context links to Loki logs. Do not rely on manual dashboard edits.
  - Define measurable SLIs and provisional SLOs. Add version-controlled alert rules for sustained failure rate, latency, outbox backlog, dead-letter growth, and consumer lag, with one actionable runbook entry per alert.
  - Add deterministic tests and a documented local demonstration that produce one distributed trace spanning inbound order HTTP, mock-provider HTTP, PostgreSQL/order/payment work, capture accounting and ledger, outbox append/publish, Kafka consume, and notification persistence.
  - Keep operator inspection/retry APIs and manual-retry span links out of 7A; those belong to 7B.
- Validation commands:
  - `./gradlew :application:integrationTest --tests '*TracePropagationIntegrationTest'`
  - `./gradlew :application:integrationTest --tests '*SensitiveTelemetryIntegrationTest'`
  - `./gradlew :application:integrationTest --tests '*ObservabilityIntegrationTest'`
  - `./gradlew composeValidate documentationCheck`
  - `./gradlew clean verify`
- Observable acceptance:
  - One demonstrated successful order has a connected or correctly parented trace across HTTP server/client, provider, PostgreSQL, ledger, outbox publisher, Kafka producer/consumer, and notification worker; asynchronous boundaries use W3C context and accurate span relationships.
  - Every HTTP response returns a valid correlation ID, and the same safe value is available in relevant structured logs and trace context without becoming a metric label.
  - Prometheus scrapes only the protected management interface. Provisioned Grafana dashboards query Prometheus and link the demonstrated trace and logs through Tempo and Loki.
  - Business metrics and alert expressions have bounded label sets; tests reject identifiers or personal/security data as dimensions.
  - Published SLI/SLO definitions state measurement windows and provisional targets. Each required alert has a runbook with impact, confirmation, mitigation, recovery, and escalation steps.
  - Seeded token and personal-data markers never appear in logs, traces, metrics, event headers, DLT metadata, or error responses.
  - Telemetry backend unavailability is bounded and does not change the business outcome.

### Milestone 7B — Add secured operator recovery

- Status: Proposed
- Approval gate: Authenticated/bounded replay follow-up R4 must either be completed separately or explicitly incorporated into an approved revision of 7B. Approval of 5D or 7A does not approve operator recovery.
- Intended outcome: Authorized operators can inspect and retry payment, outbox, and DLT failures through distinct permissions, server-derived identity, bounded commands, and immutable audit evidence.
- Implementation work:
  - Complete operator OpenAPI schemas, separate read/retry scopes, pagination, safe projections, and internal-ingress guidance.
  - Implement one-active-retry constraints, immutable `202` command replay, leased multi-instance worker claiming/takeover, stale-worker rejection, append-only audit, server-controlled dispatch, and operation-specific resolution evidence.
  - Implement payment resume with the original provider key, outbox cycle reset with cumulative attempts retained, and validated DLT replay with original event identity/content, stripped retry headers, new retry correlation/trace, and retry-request causation.
  - Derive actor identity from authenticated credentials, enforce cooldown/attempt limits and documented break-glass approval, and keep inspection permission distinct from replay permission as required by separately tracked abuse follow-up R4.
  - Add operator-request spans and links to stored originating context without changing the business-event envelope. Preserve secure mock/provider/JWT startup defaults.
- Validation commands:
  - `./gradlew :application:integrationTest --tests '*OperatorApiIntegrationTest'`
  - `./gradlew :application:integrationTest --tests '*OperatorRetryIntegrationTest'`
  - `./gradlew :application:integrationTest --tests '*OperatorReplayIntegrationTest'`
  - `./gradlew clean verify`
- Observable acceptance:
  - Customer tokens cannot access operator routes; operator read and retry scopes are distinct, and caller input cannot assert audit identity.
  - Concurrent or repeated retry commands schedule one server-selected action, respect cooldown/attempt/break-glass limits, and create immutable audit evidence.
  - Two instances claim one retry once; expired takeover succeeds and stale completion is rejected.
  - Payment/outbox/notification recovery remains idempotent and resolves the failed operation on success or confirmed duplicate.
  - Operator retry spans are linked to the stored originating context without mutating the original event or business correlation.
  - Seeded secret markers never appear in operator logs, traces, event headers, DLT metadata, or API responses.

### Separately tracked abuse-case follow-ups

- **R3 — Quota and idempotency retention (`Proposed`).** The detailed design remains Finding 4/R3 in `.agent/plans/2026-07-14-abuse-case-remediation.md`. It requires separate maintainer approval and is a production gate before sustained or multi-tenant public use. It is not part of Milestone 5D, Milestone 6, or Milestone 7A.
- **R4 — Authenticated and bounded replay (`Proposed`).** The detailed design remains Finding 5/R4 in `.agent/plans/2026-07-14-abuse-case-remediation.md`. It requires separate maintainer approval and is a production gate before replay or secured operator recovery is enabled. It may be completed before 7B or incorporated only through an explicitly approved 7B plan revision; it is not part of Milestone 5D or Milestone 7A.

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
| Provider circuit and concurrency isolation | Resilience4j circuit breaker and semaphore bulkhead 2.4.0 | A mature thread-safe state machine and bounded admission control are safer than a repository-local implementation; direct core APIs avoid Spring AOP/annotation coupling |

The JDK does not provide Spring MVC integration, bean validation, JDBC pooling and transaction configuration, append-only schema migration, Kafka protocol integration, JWT resource-server validation, production health endpoints, or vendor-neutral telemetry export. The Spring Boot starters supply those capabilities as one tested platform and are preferable to hand-assembling their transitive libraries. Their operational and security implications are controlled as follows:

- the web and validation stack introduces an embedded server and JSON/input-processing surface, so later API milestones must add request limits, contract tests, authorization, and supported-version patching;
- JDBC, PostgreSQL, and Flyway introduce database credentials, connections, and migration authority, so configuration is externalized, tests use disposable PostgreSQL, and deployment must separate migration-owner and runtime roles before production;
- Kafka introduces background network clients and broker credentials. The scaffolding milestone added dependencies without startup behavior; Milestone 5B activates the publisher and consumers only through explicit configuration, keeps topic auto-creation disabled, and externalizes connection/security settings;
- the resource-server starter changes the security surface and will require an explicitly approved issuer, audience, algorithms, scopes, and failure behavior before business routes exist;
- Actuator exposes status-only liveness/readiness and Prometheus on the separate management listener selected by Milestone 5D. Aggregate health, components, details, and `info` remain unavailable, while `docs/deployment-security.md` requires network isolation; and
- Micrometer/OpenTelemetry can create outbound traffic and high-cardinality or sensitive telemetry, so exporters are disabled in the context test and later milestones must configure bounded export, redaction, safe dimensions, and collector trust.
- Resilience4j adds in-memory per-instance state and fast rejection. It cannot coordinate breaker state between instances and must never decide financial outcome; stable provider operation IDs and lookup-first recovery remain authoritative. Version 2.4.0 is pinned through its BOM because Spring Boot does not manage this family.

Test/build-only dependencies:

- the Testcontainers BOM plus PostgreSQL, Kafka, Toxiproxy, and Redis-compatible modules for real integration and failure boundaries without version drift;
- Spring Modulith and ArchUnit for complementary module verification and repository architecture rules;
- a compatible OpenAPI validation Gradle plugin without server generation;
- Spotless and Checkstyle for formatting/static analysis; and
- Spring Security/MockMvc, Awaitility where polling is unavoidable, and in-memory OpenTelemetry export for deterministic tests.

Do not add Lombok, an ORM, MapStruct, Redis, Debezium, embedded Kafka, H2, or a money library. Resilience4j is limited to direct circuit-breaker and semaphore-bulkhead core APIs; retry classification/backoff and payment state remain explicit application/domain behavior rather than annotations.

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

Provide documented explicit local/test/demo configuration with PostgreSQL 18.4 and Kafka 4.3.1 containers. Integration tests run the separate mock-provider fixture on an ephemeral loopback origin; manual local HTTP use requires a separately running implementation of its support contract. The main artifact excludes mock controls. Production deployment must not configure the demonstration provider and requires a separately approved real-provider URL, authentication, TLS, reconciliation, and credential design. Local secrets are placeholders or generated ephemeral values and are ignored by Git.

## Failure modes

| Failure | Required behavior and evidence |
| --- | --- |
| Missing/invalid auth, key, payload | Reject before business rows; safe problem response |
| Concurrent same-key order | One owner claim/workflow; replay or bounded `503` while a provider call is still active |
| Process dies with idempotency in progress | Resumer uses the existing order/payment; a stale active provider stage is reconciled by lookup after its configured deadline |
| Provider latency | Bounded client timeout; no open DB transaction |
| Provider decline | Terminal decline; no retry, ledger, or outbox |
| Provider temporary failure | One automatic retry; exhaustion becomes an owner-readable pending state while general operator recovery remains future work |
| Provider timeout or process loss after send | Persist/retain unknown state; lookup same operation key before resend |
| Malformed/contradictory provider response | Durable order/payment `FAILED`; cached `502` and Location; safe non-retryable operation; no ledger/outbox |
| Provider capture succeeds; local finalization fails | Local transaction rolls back; lookup confirms capture; repeat idempotent finalization |
| Illegal/stale transition | Guarded update affects zero rows and becomes conflict/integrity evidence, not a blind overwrite |
| Unbalanced/incomplete ledger | Deferred constraint aborts the capture-accounting transaction; no journal/outbox/accounted state commits |
| Kafka unavailable | Completed business state remains; the outbox stays durable as pending/in-flight/failed evidence for publisher recovery and runbook inspection |
| Broker ack then publisher crash | Lease expiry republishes same event; consumer deduplicates |
| Two publisher instances | `SKIP LOCKED`, owner tokens, and leases prevent simultaneous ownership; stale owners cannot mark rows |
| Consumer DB failure before commit | Offset not committed; retry handles record again |
| Consumer DB commit then offset failure | Redelivery finds same event ID/hash and performs no duplicate notification |
| Event ID with changed payload | Integrity failure; no domain effect; direct DLT |
| Transient consumer failure | Initial attempt plus three bounded pause-based retries, then DLT |
| DLT publication failure | Source offset remains uncommitted and record is redelivered |
| DLT catalog duplication | Original topic/partition/offset unique constraint makes cataloging idempotent |
| Concurrent operator retries | One active retry and one command result; all others replay/conflict |
| Retry worker crash/lease expiry | One owner/version-guarded takeover; stale worker cannot execute or complete |
| DLT catalog database outage | DLT offset remains uncommitted; bounded pause/retry and alert; no recursive DLT |
| Telemetry backend unavailable | Bounded exporter drops/retries telemetry without changing business outcome |
| Invalid correlation/trace header | Safe correlation replacement/new trace; untrusted value is never logged verbatim |
| Provider connection saturation | Semaphore bulkhead rejects excess calls immediately with a stable temporary result; no unbounded queue |
| Repeated provider transport failure | Count-window circuit opens, rejects without I/O, permits bounded half-open probes, and closes only after recovery |
| Provider read/overall timeout | Cancel the asynchronous call, classify the outcome as unknown, persist it, and reconcile by stable request ID before resend |
| Temporary database outage | Startup/readiness reports unavailable; transactional consumers roll back and retain offsets for retry |
| Shutdown with active HTTP/Kafka/outbox work | Stop ingress, drain tracked work within the configured phase timeout, and emit explicit failed-drain state/log if the deadline expires |
| Unsafe fault injection configuration | Production startup rejects it; only allowlisted local/test profiles may bind bounded injection points |

## Validation and acceptance

### Required environment

- JDK 25 selected by the Gradle toolchain.
- Docker-compatible runtime available to Testcontainers.
- Sufficient local resources for PostgreSQL 18 and Kafka 4.3.1 containers.
- No externally installed Gradle, database, broker, or identity provider is required for automated tests.

### Automated strategy

- **Unit/parameterized tests:** every allowed/forbidden state transition; money bounds/overflow; request normalization; provider result classification; retry eligibility; safe event serialization.
- **Property tests where useful:** sequences of state commands never escape the transition graph; generated debit/credit postings remain balanced or fail validation.
- **PostgreSQL integration tests:** Flyway from empty with migration owner; runtime-role DDL/immutability denial; all checks/FKs/unique/partial indexes; idempotency races/lease ownership/stale completion; optimistic-state races; parent/entry balance triggers; ledger/account immutability; atomic finalization; outbox claiming/lease/reset; inbox hash and semantic-effect conflicts; terminal-DLT evidence; retry-worker lease/takeover.
- **HTTP/provider tests:** both OpenAPI contracts; public status/header/schema cases including replayable `502`; JWT scope/ownership/RS256/rotation/JWKS outage; all mock scenarios; timeout-after-provider-effect; same-key provider semantics; payment-reference clearing; response redaction.
- **Kafka integration tests:** key/envelope/headers; broker outage; ack/status crash duplicate; transport and semantic duplicates; consumer commit/offset crash; direct/exhausted/malformed DLT; malformed-DLT partition progress; failed DLT send; catalog DB outage/redelivery; sanitized catalog; replay header reset/correlation/resolution.
- **Resilience/Toxiproxy tests:** provider latency, reset, read/overall timeout, temporary PostgreSQL unavailability, Kafka bootstrap unavailability/recovery, circuit open/half-open/close, semaphore saturation, bounded drain, and profile-gated fault injection.
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
- exactly one notification for the immutable capture semantic effect, even if it is delivered under more than one event ID.

## Rollback and recovery

- Before a milestone starts, keep later migrations and contracts out of scope. Roll back application changes through normal version control; never rewrite a merged migration.
- Initial additive migrations are forward compatible within their milestone. Any schema correction after merge is a new Flyway migration.
- Do not automatically roll back or delete captured/ledger data. Resolve inconsistent local state through provider lookup and idempotent forward finalization; financial corrections require new ledger records.
- A failed deployment may return to the previous application only if the schema and event contract remain backward compatible. Otherwise deploy a forward fix.
- Outbox and inbox records are durable recovery evidence. Do not purge or manually mark them to recover an incident.
- After Milestone 7B, operator recovery uses the secured API, stable provider/event identity, current-state guards, and audit. Until then, replay remains development-only and is a production blocker unless disabled with its producer authority revoked; direct database/Kafka manipulation is emergency-only and requires a separately approved runbook.
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
- [x] `2026-07-13 15:41Z` — Recorded explicit maintainer approval for transactional outbox and Kafka delivery, inspected the clean worktree and existing `READ_COMMITTED` capture-accounting boundary, and revised Milestone 5B to include outbox publication, inbox/notification, bounded DLT handling, Kafka trace propagation, and narrow audited command-line replay while excluding public order/payment/operator APIs.
- [x] `2026-07-13 16:17Z` — Implemented V004/V005, the atomic capture-accounting/outbox append, leased multi-instance publisher, versioned envelope and Kafka headers, notification inbox/deduplication, bounded consumer retries and DLT catalog, audited command-line replay, Kafka Testcontainers coverage, crash-window proof, and accurate at-least-once documentation.
- [x] `2026-07-13 16:20Z` — Clean Java 25 `-Xlint:all -Werror` compilation passed for 123 production sources plus every unit, integration, and architecture source. All 27 unit tests and all five Spring Modulith/ArchUnit checks passed through the cached JUnit launcher; Google Java Format, ktlint, and Checkstyle checks passed.
- [x] `2026-07-13 16:23Z` — Required `./gradlew clean verify` and Kafka Testcontainers execution were initially blocked because the sandbox denied Gradle's wildcard-IP socket and `/var/run/docker.sock`; the later Docker-enabled verification entries below resolve this item.
- [x] `2026-07-14 06:18Z` — Recorded explicit maintainer approval to expand the active Milestone 5B with resilience hardening. Docker 29.6.1 and Compose 5.3.0 became available; the Gradle 9.6.1 Wrapper uses Java 25.
- [x] `2026-07-14 06:18Z` — Ran the previously blocked `./gradlew --no-daemon clean verify --console=plain`. Formatting, static analysis, unit, architecture, Compose, OpenAPI, documentation, and integration compilation passed; the Kafka suite exposed one real baseline defect: DLT catalog `attempt_count` recorded `1` instead of the required `4`. The resilience retry work must correct and retain this evidence rather than weakening the assertion.
- [x] `2026-07-14 06:40Z` — Implemented ordered provider deadlines, explicit retry classification, Resilience4j circuit/bulkhead controls, bounded HTTP shutdown, operations-owned drain/readiness/startup/topic validation, pause-based bounded Kafka intake, local/test fault hooks, DLT attempt evidence, Toxiproxy scenarios, ADR 0009, and aligned architecture/security/runbook documentation.
- [x] `2026-07-14 06:40Z` — Passed formatting, Java 25 `-Xlint:all -Werror`, Checkstyle, all unit tests, Spring Modulith/ArchUnit, both OpenAPI validators, and documentation checks. Focused Docker-backed execution started but the Docker Desktop daemon stopped answering during Testcontainers discovery, before any application assertion ran.
- [x] `2026-07-14 07:03Z` — With Docker 29.6.1 healthy, passed provider latency/timeout/reset and PostgreSQL/Kafka unavailability/recovery Toxiproxy tests. Corrected the Kafka proxy target to its host-reachable broker listener after the first topology advertised an internal-only hostname.
- [x] `2026-07-14 07:07Z` — Ran `./gradlew --no-daemon clean verify --console=plain`; all 65 formatting, static-analysis, unit, PostgreSQL/Kafka/Toxiproxy integration, architecture, Compose, OpenAPI, and documentation task actions completed successfully in 3m 46s. The corrected Kafka retry/DLT tests retain four-attempt evidence, and duplicate-delivery tests retain one ledger and notification side effect.
- [x] `2026-07-14 07:31Z` — Recorded explicit maintainer approval for Milestone 5C, read repository governance/current plan, and inspected the existing resource-server, owner-filtered order query, immutable V005 replay audit, Keycloak realm, input/error handling, configuration, tests, and dirty worktree from the completed resilience milestone.
- [x] `2026-07-14 07:31Z` — Fixed scope before implementation: harden current routes and reserve operator authorization without implementing operator recovery; add no migration/datastore; use a bounded per-instance JDK limiter plus deployment-edge global limiting; use a pinned build-time scanner rather than a production dependency. Docker became unavailable during scanner-image inspection, so container checks remain pending while implementation proceeds.
- [x] `2026-07-14 08:42Z` — Implemented exact JWT issuer/audience/algorithm validation, allowlisted realm-role and scope authorization, owner-filtered reads, strict bounded input, secure headers, per-subject write throttling, immutable replay-audit verification, local Keycloak roles/scopes/audience, pinned Trivy tooling, negative/security tests, and aligned contracts and documentation.
- [x] `2026-07-14 08:42Z` — With Docker 29.6.1 healthy, passed focused security integration tests, validated a fresh-volume Keycloak import, and started all nine Compose dependencies with temporary overrides for occupied PostgreSQL/Valkey host ports. Every service became healthy and the native Kafka 4.3.1 KRaft broker answered a metadata-quorum request.
- [x] `2026-07-14 08:42Z` — Ran Java 25/Gradle 9.6.1 `./gradlew --no-daemon clean verify --console=plain`; all formatting, static-analysis, unit, PostgreSQL/Kafka/Toxiproxy integration, architecture, Compose, OpenAPI, and documentation checks passed in 2m 7s after correcting one test-only line-length finding.
- [x] `2026-07-14 08:44Z` — Captured the initial vulnerability-gate result without hiding it. Pinned Trivy found no committed secret or fixed HIGH/CRITICAL application-artifact dependency, and `apache/kafka-native:4.3.1` was clean, but eight official Compose images retained fixed findings. Milestone 5C correctly remained in progress pending explicit remediation or risk authority.
- [x] `2026-07-14 10:55Z` — Refreshed the Trivy databases and official registry/release evidence, enumerated every remaining image/target/package/version/CVE/fix tuple, assessed local reachability and mitigations, and recorded the maintainer-authorized 30-day local-development-only acceptance. No application-artifact or repository-secret exception was added.
- [x] `2026-07-14 10:55Z` — Replaced Prometheus 3.13.0 with clean official patch 3.13.1 and moved PostgreSQL 18.4 from Alpine to the same-version official Debian variant to remove the fixed `c-ares` finding. Added an exact digest-bound policy that prints all findings and fails on new, changed, stale, undocumented, or expired tuples. Final security scan and clean verification remain pending.
- [x] `2026-07-14 11:06Z` — Ran `scripts/security-scan` with refreshed intelligence. The repository secret/configuration and packaged application gates passed with no exception path; Kafka and Prometheus scanned clean; Trivy printed every remaining Compose finding; and all remaining tuples matched exact unexpired records LF-DEV-2026-001 through LF-DEV-2026-017. The command exited successfully without a broad ignore.
- [x] `2026-07-14 11:13Z` — Started all nine Compose dependencies with temporary `POSTGRES_PORT=15432` and `VALKEY_PORT=16379` overrides for occupied host defaults; every dependency, including PostgreSQL 18.4 Trixie and Prometheus 3.13.1, became healthy. Ran `./gradlew --no-daemon clean verify --console=plain`; all 65 formatting, static-analysis, unit, PostgreSQL/Kafka/Toxiproxy integration, architecture, Compose, OpenAPI, and documentation task actions passed. Milestone 5C is complete.
- [x] `2026-07-15 11:14Z` — Reconciled the canonical MVP plan with the abuse-case design record. Recorded approved Milestone 5D containing R1/R2, kept R3/R4 as separately proposed follow-ups, placed Milestone 6 after 5D, and split the previous combined observability/recovery milestone into Proposed 7A observability and Proposed 7B operator recovery without implementing runtime behavior.
- [x] `2026-07-15 11:19Z` — Passed `./gradlew --no-daemon spotlessCheck documentationCheck --console=plain`, `git diff --check`, and `./gradlew --no-daemon clean verify --console=plain`; the full lifecycle completed 65 formatting, static-analysis, unit, PostgreSQL/Kafka/Toxiproxy integration, architecture, Compose, OpenAPI, and documentation task actions successfully.
- [x] `2026-07-15 11:22Z` — Re-read repository governance and both planning records, preserved the existing planning-only worktree changes, and marked the maintainer-approved Milestone 5D In Progress. Implementation remains limited to R1 followed by R2.
- [x] `2026-07-15 12:39Z` — Implemented Milestone 5D R1: separate configurable management listener, application-port Actuator denial, status-only health, management-only Prometheus contract, same-port startup rejection, one lazy lifecycle-managed Kafka Admin, and a bounded success/failure readiness cache that coalesces 100 concurrent callers while startup remains uncached.
- [x] `2026-07-15 12:39Z` — Implemented Milestone 5D R2 through additive V006/V007: event-ID/hash transport idempotency remains, a database-unique versioned capture-notification effect uses immutable ledger transaction identity and content-conflict detection, and terminal invalid DLT input commits immutable sanitized evidence by actual DLT coordinates before acknowledgement. Added exact low-cardinality metrics, four Prometheus rules/runbooks, deployment/Kafka ACL guidance, ADRs 0010–0012, and no quarantine topic, replay hardening, quota/retention, or business workflow behavior.
- [x] `2026-07-15 12:39Z` — Passed focused operations/management, notification/Kafka, and V005 migration-compatibility tests. The tests prove one probe for 100 callers, cache failure expiry, transport/semantic duplicate separation, concurrent semantic convergence, conflicting-content DLT, all six terminal input classes, evidence immutability/idempotent redelivery, database-failure offset retention/recovery, later partition progress, compatible V005 upgrade, and transactional fail-closed duplicate migration.
- [x] `2026-07-15 12:51Z` — Validated Compose and the Prometheus configuration/rules (`4 rules`), passed `git diff --check`, and ran the final Java 25/Gradle 9.6.1 `./gradlew --no-daemon spotlessApply clean verify --console=plain` after adding PostgreSQL-microsecond normalization coverage for nanosecond `Instant` input. All 69 formatting, static-analysis, unit, PostgreSQL/Kafka/Toxiproxy integration, architecture, Compose, OpenAPI, and documentation task actions passed in 7m 31s. Milestone 5D is Complete; Milestone 6 and 7A remain Proposed.
- [x] `2026-07-15 16:59Z` — Ran `scripts/security-scan` against the complete Milestone 5D worktree. Repository-secret and packaged-application gates passed without exceptions; all Compose findings remained visible and matched exact, digest-bound, unexpired local-development-only records. Revalidated Compose plus Prometheus configuration and all four abuse-control rules.
- [x] `2026-07-15 16:59Z` — Performed a separate read-only review of the complete uncommitted diff across management security/probe lifecycle, semantic-effect concurrency and transaction rollback, V006/V007 compatibility and immutability, DLT acknowledgement/recovery, metrics, alerts, tests, and operational claims. No Critical, High, or Medium finding in the required financial consistency, duplicate-side-effect, lost-event, blocked-partition, security-bypass, data-exposure, or unbounded-resource categories remained.
- [x] `2026-07-15 17:00Z` — Passed final `git diff --check` and `./gradlew --no-daemon clean verify --console=plain`: 66 formatting, static-analysis, unit, PostgreSQL/Kafka/Toxiproxy integration, architecture, Compose, OpenAPI, and documentation task actions succeeded (24 executed, 40 restored from the verified build cache, 2 up-to-date). No required task was skipped.
- [x] `2026-07-15 17:24Z` — Recorded explicit maintainer approval for Milestone 6, inspected governance, the canonical plan, normative product/domain/API/data/threat/architecture documents, ADRs 0003–0012, all order/payment/ledger/messaging/notification APIs, migrations, module boundaries, and the complete integration-test inventory, and marked only Milestone 6 In Progress. The implementation boundary is short PostgreSQL initialization/finalization transactions around provider-free work, provider lookup/calls outside transactions, the existing atomic ledger/outbox transaction, and asynchronous Kafka/notification after the HTTP result.
- [x] `2026-07-15 17:54Z` — Implemented the OpenAPI-first complete public workflow, narrow orders/payments APIs, V008 final-state invariants, stable provider-operation recovery, idempotent ledger/outbox finalization, and truthful asynchronous response semantics. Added focused PostgreSQL, HTTP, provider timeout/lookup, crash, concurrency, database-constraint, Kafka-available/unavailable, and notification tests plus ADR 0013 and aligned product/domain/API/data/threat/architecture/README/runbook documentation. Focused contract, module, security, and integration tests pass after correcting two test-fixture-only compile/timestamp issues without weakening production constraints.
- [x] `2026-07-15 17:56Z` — Ran `scripts/security-scan`. The committed-content secret/configuration and packaged Java application gates passed without exceptions; Kafka/Prometheus remained clean; every official Compose-image finding stayed visible and matched an exact digest-bound, unexpired local-development-only record. No production risk acceptance or new suppression was introduced.
- [x] `2026-07-15 17:58Z` — Strengthened the Kafka-unavailable proof by pausing a live Testcontainers broker after startup while the workflow completed and left its outbox `PENDING`; added successful bounded capture-temporary retry coverage. Both focused integration classes passed from clean task execution.
- [x] `2026-07-15 18:03Z` — Performed the separate read-only review of the complete uncommitted workflow for false atomicity, unsafe retries, duplicate financial/notification effects, stale transitions, idempotency races, object authorization, sensitive-data exposure, runtime Kafka loss, and misleading API/docs. Fixed the active-timeout environment-name mismatch, historical-order OpenAPI nullability, false single-transaction wording, a weak disabled-Kafka fixture, and formatter/static-rule compatibility. No Critical or High finding, or Medium finding in the required financial consistency, lost-event, duplicate-side-effect, security-bypass, data-exposure, or unbounded-resource categories, remains.
- [x] `2026-07-15 18:03Z` — Passed final `git diff --check` and Java 25/Gradle 9.6.1 `./gradlew --no-daemon clean verify --console=plain`: all 66 formatting, static-analysis, unit, PostgreSQL/Kafka/Toxiproxy integration, architecture, Compose, OpenAPI, and documentation actions succeeded in 1m 54s (33 executed, 30 restored from the verified build cache, 3 up-to-date). Milestone 6 is Complete; no required task was skipped.

## Surprises and discoveries

- The repository has no Gradle lifecycle, so the first application milestone must establish every required check before later implementation can satisfy the Definition of Done.
- The requested ExecPlan path conflicts with the default `.agent/plans/...` location. The maintainer's explicit path is treated as a one-plan exception; duplicating a living plan would create drift.
- The accepted architecture originally deferred the base package, concrete feature modules, and Gradle project structure. The maintainer's Milestone 1 approval selected `com.ledgerflow`, six feature projects, and one deployable application project. ADRs 0003 through 0007 and 0009 through 0013 now record the accepted implemented boundaries; ADRs 0002 and 0008 remain proposed.
- PostgreSQL row `CHECK` constraints cannot enforce a balance across multiple entries. A deferred constraint trigger on both parent and entry changes is necessary to reject even an empty ledger transaction.
- Provider capture and the local database cannot be atomic. Stable provider operation keys, lookup, and idempotent local finalization are required; a database transaction held across HTTP would not solve this gap.
- Bounded Kafka retries use container pausing rather than sleeping the consumer thread. The affected work waits while polling remains alive, preserving a smaller topic topology without risking long blocking backoffs; sustained failures still move to DLT.
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
- Keycloak does not re-import a realm that already exists. The updated local realm was therefore verified with an isolated fresh PostgreSQL volume; existing developer data was preserved, and the README calls out deliberate `dev-reset` when an import update is required locally.
- Trivy filesystem mode did not inspect dependencies nested in the executable Spring Boot JAR. The scanner uses its root-filesystem target plus the Java vulnerability database for that artifact, reports one language-specific file, and allows fifteen minutes for the large first-run Java database download.
- The refreshed 2026-07-14 Trivy database reports fixed HIGH/CRITICAL findings in current official Grafana, Loki, Tempo, OpenTelemetry Collector, Keycloak, Valkey, and PostgreSQL entrypoint binaries. Compatible official Kafka and Prometheus images scan clean, and a same-version PostgreSQL base-image change removes its OS-package finding. The remaining upstream risk is accepted only for exact local-development digests through 2026-08-13; scanner output remains visible and any drift fails closed.
- A separate Spring Boot management child context needs its own path-scoped highest-precedence security chain; otherwise the application catch-all chain also matches management requests. The integration test proves only the approved management paths remain reachable and the application listener serves none.
- PostgreSQL `jsonb` canonicalizes object key order, so terminal-evidence redelivery compares safe headers as `jsonb` rather than raw serialized text. This preserves idempotency for semantically identical allowlisted headers with different property order.
- A semantic-conflict exception subtype is necessary to keep the `semantic_conflict` metric exact; generic transport-coordinate or event-ID integrity conflicts use the separate bounded `transport_conflict` outcome.

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
- **2026-07-13 — Use one initial consumer attempt plus three bounded retries at 1/5/30 seconds.** Then publish to DLT; non-retryable validation or integrity failures go directly there. This superseded the original retry-topic proposal. ADR 0009 later refined the waits from blocking to container-pausing so polling remains alive.
- **2026-07-11 — Use JWT resource-server security.** Operator retry is privileged; the application consumes but does not issue tokens.
- **2026-07-11 — Use UUIDv4 from the JDK.** Superseded for order IDs by the maintainer's 2026-07-13 UUIDv7 requirement and PostgreSQL 18's native `uuidv7()` support; no dependency is added.
- **2026-07-13 — Make order creation one short idempotency transaction.** Claiming the unique scoped key, inserting the order, and persisting the original response snapshot commit together. This avoids committed leases or incomplete resources before external payment work exists; ADR 0003 must reflect this accepted slice-specific boundary.
- **2026-07-13 — Restrict the MVP currency to INR.** The currency remains explicit and validated in API, domain, and database layers; multi-currency and FX remain out of scope.
- **2026-07-13 — Declare existing Spring capabilities directly in the orders feature.** The multi-project compiler requires the orders library to declare Web MVC, validation, JDBC, Jackson, and OAuth resource-server APIs it imports. These are the same Spring Boot-managed starters already present in the deployable application, so no new production library or version is introduced. JDBC adds the PostgreSQL transaction boundary; resource-server validation adds issuer/JWK/audience trust and scope enforcement; no provider, broker, cache, or persistence technology is added.
- **2026-07-13 — Reuse Spring Boot-managed JDBC/Jackson dependencies and the JDK HTTP client for payments.** The payments feature declares the already-approved Data JDBC and Jackson starters because its adapter compiles against those APIs. The JDK remains the HTTP client. ADR 0009 later adds direct Resilience4j circuit/bulkhead cores while keeping retry and classification as explicit domain/application behavior.
- **2026-07-13 — Treat timeout and post-call crashes as unknown outcomes, not temporary failures.** Authorization/capture recovery always queries the provider with the persisted request ID; only `NOT_FOUND` permits a same-ID resend. This accepts ADR 0004 for the payment boundary and prevents duplicate provider effects.
- **2026-07-13 — Persist provider success as `CAPTURE_CONFIRMED`.** `CAPTURE_ACCOUNTED` now means the balanced journal also committed; final `CAPTURED` remains reserved for later order/outbox finalization.
- **2026-07-13 — Split ledger posting from final outbox activation at the maintainer's explicit scope boundary.** Milestone 5A transitions `CAPTURE_CONFIRMED -> CAPTURE_ACCOUNTED` in the same transaction as the journal. At that point Milestone 5B was unapproved; the later transactional-outbox approval activated only the outbox/Kafka/notification portion while final `CAPTURED`, order completion, and HTTP orchestration moved to Milestone 6. ADRs 0004 through 0006 record the resulting boundaries.
- **2026-07-13 — Use Spring `READ_COMMITTED`, payment-row locking, and uniqueness for ledger idempotency.** Same-payment writers serialize on `SELECT ... FOR UPDATE`; unique payment/source indexes are database backstops. No new production dependency is necessary because existing Spring transaction/JDBC capabilities cover the boundary. ADR 0005 records the isolation decision.
- **2026-07-13 — Export `payments.api` and `ledger.api` as Spring Modulith named interfaces.** Compile-only annotation dependencies use the already selected Modulith BOM and add no runtime library. This makes the documented API/internal distinction enforceable by the existing architecture check.
- **2026-07-13 — Attach the first payment-captured outbox event to the existing capture-accounting transaction.** `LedgerPostingService.postPaymentCapture` is the current complete business transaction: it locks the provider-confirmed payment, writes the balanced journal, and records `CAPTURE_ACCOUNTED`. A mandatory `messaging.api` append makes those effects and the event atomic without prematurely adding final order/public workflow behavior. This accepts ADR 0006 for the approved scope.
- **2026-07-13 — Use a canonical `schemaVersion` envelope with aggregate and causation identity.** The immutable envelope contains the fields required by the maintainer; the initial causation ID is the stable provider capture request ID, while replay request identity remains transport/audit metadata so replay does not mutate the event body.
- **2026-07-13 — Deliver replay as a narrow audited command-line operation.** Validated DLT catalog records can be requested with an explicit actor/reason and republished with acknowledgement and append-only audit; malformed records are never replayable. The proposed secured operator HTTP API remains outside this milestone.
- **2026-07-14 — Use Resilience4j 2.4.0 direct core circuit-breaker and semaphore-bulkhead APIs.** Spring Boot does not supply these state machines, and a mature thread-safe implementation is safer than custom concurrency code. Do not add its Spring/AOP starter or generic retry; explicit result classification and payment recovery remain authoritative. ADR 0009 is required.
- **2026-07-14 — Put cross-module drain/startup/fault controls in the existing `operations` feature.** A minimal named API lets external-work adapters register in-flight work without exposing their internals. Health and profile guards stay operations-owned; business modules retain their own failure semantics. ADR 0009 records the cross-cutting boundary.
- **2026-07-14 — Use bounded Kafka polling plus container pausing for backoff.** `max.poll.records`, typed concurrency, pause-immediate behavior, and explicit shutdown timeouts bound memory and allow heartbeats during retry delays. Record acknowledgment and inbox idempotency remain unchanged.
- **2026-07-14 — Require both scope and allowlisted realm role.** Standard Spring scope conversion remains authoritative for OAuth scopes; a small converter adds only `customer`, `operator`, and `admin` from Keycloak `realm_access.roles`. Order ownership remains a database predicate. No ADR is required because this completes the already proposed security design without changing module or data ownership.
- **2026-07-14 — Use a bounded per-instance write limiter without a new datastore.** The active application has one public write route and Valkey integration is explicitly out of scope. A JDK implementation avoids a production dependency and stores only bounded hashes of principal keys; deployment ingress must enforce global and unauthenticated volumetric limits across instances. Record this limitation in security documentation; no ADR is required.
- **2026-07-14 — Use a pinned Trivy container for dependency, secret/configuration, and Compose-image scanning.** The scanner is an explicit build/CI tool rather than runtime code. Pin its image by version and digest, fail on fixed HIGH/CRITICAL findings, and do not hide findings without expiring documented governance. The scanner requires privileged read access to the local Docker socket for image analysis; document that operational implication. No production dependency or ADR is required.
- **2026-07-14 — Replace the local Kafka JVM image with Apache's official native image at the same 4.3.1 release.** The scanner found fixed HIGH Alpine, Jackson, and unused telnet-library findings in `apache/kafka:4.3.1`; `apache/kafka-native:4.3.1` provides the same approved Kafka/KRaft release and reports none. Its health check uses the image's `nc` utility because the native distribution intentionally omits JVM administration scripts. This is a local infrastructure artifact remediation, not a Kafka protocol/version or production-topology decision.
- **2026-07-14 — Initially prefer current official image variants/releases when scans materially reduce findings without changing a service contract (superseded in part below).** PostgreSQL remained 18.4 but initially moved to its Alpine 3.24 variant, Grafana remained 13.1.0 but moved to its Ubuntu variant, and Prometheus moved from the expiring 3.5 LTS line to current LTS 3.13.0. These scan-driven local-development changes reduced fixed HIGH findings but did not silently accept the remaining upstream findings. The following decision supersedes the PostgreSQL and Prometheus pins after refreshed evidence; the Grafana decision remains current.
- **2026-07-14 — Refine compatible image remediation after refreshing scanner and registry evidence.** Prometheus moves from 3.13.0 to its clean official 3.13.1 patch. PostgreSQL remains 18.4 but moves from Alpine to the official Debian Trixie variant, removing the fixed `c-ares` finding while preserving its service contract and named-volume layout. Current Valkey Debian and Grafana variants do not remove their application findings safely, so no scanner-driven downgrade or major service change is made. This supersedes only the affected local image pins in the preceding decision.
- **2026-07-14 — Accept only exact, expiring local-development container risk.** The maintainer's direct instruction authorizes the records in `docs/security/local-development-container-risk-register.md` through 2026-08-13 or compatible fixed-image availability, whichever is earlier. The machine policy matches image tag, digest, scanner target, package, installed version, and CVE; Trivy prints every finding and fails on any drift. Repository-secret and packaged-application gates are unchanged, and the acceptance is explicitly invalid for production. This is scanner governance for local infrastructure, not a production architecture decision or a production dependency.
- **2026-07-15 — Make this plan the sole execution authority for pre-observability work.** The existing abuse-case plan remains a supporting evidence/design record, but only canonical Milestone 5D controls approval, progress, and completion for R1/R2. R3/R4 remain separately proposed. This removes competing milestone status without duplicating the detailed finding analysis. No ADR is required for planning ownership.
- **2026-07-15 — Split observability from operator recovery.** Milestone 7A owns telemetry, metrics, logs, dashboards, SLOs, alerts/runbooks, and the complete business-flow trace, and cannot be approved until 5D and 6 are Complete. Milestone 7B owns privileged inspection/retry and depends on a separate disposition of replay hardening R4. This prevents an observability request from silently approving privileged recovery behavior. No architecture decision is accepted by this planning split.
- **2026-07-15 — Isolate and coalesce management dependency probes.** Use a distinct configurable management listener, deny Actuator on the application listener, expose status-only probes plus management-network-only Prometheus, reject equal fixed ports, reuse one lazy managed Kafka Admin, and cache/coalesce readiness for two seconds while startup remains uncached. This closes public work amplification without adding a datastore or production technology. ADR 0010 records the decision.
- **2026-07-15 — Separate notification envelope and business-effect identity.** Retain event-ID/hash transport checks and add `PAYMENT_CAPTURED_NOTIFICATION` identity version 1 keyed by immutable capture ledger transaction ID with order/payment/causation/money/time conflict comparison. This prevents re-enveloping without assuming one event per payment. ADR 0011 records the decision.
- **2026-07-15 — Persist terminal invalid DLT evidence by actual coordinates.** Missing/malformed routing and invalid event data store immutable bounded sanitized evidence before acknowledgement; database failure retains the Kafka offset and no quarantine topic is added. ADR 0012 records the decision.
- **2026-07-15 — Add no new production technology for Milestone 5D.** Direct Spring Security and Micrometer declarations in the owning feature modules describe already-present Boot-managed runtime capabilities; PostgreSQL, Kafka, Actuator, and JDK concurrency provide the controls. No manually versioned production dependency is introduced.
- **2026-07-15 — Put the concrete public workflow in `orders` and expose only narrow module APIs.** `orders.api` defines the public create/read use case and projections; `payments.api` adds only initialization, advancement, read, and guarded capture finalization needed by orders/ledger. The coordinator is specific to this flow, the Gradle graph remains acyclic, and no generic saga/workflow framework or new production dependency is introduced.
- **2026-07-15 — Use recoverable local transactions rather than false cross-system atomicity.** The initial identity transaction commits before provider I/O; each provider call/lookup occurs outside PostgreSQL; the existing journal plus outbox transaction remains atomic; and a separate provider-free transaction finalizes payment/order/idempotency after verifying that evidence. Kafka and notification are never awaited by HTTP. ADR 0013 records this decision and V008 provides a deferred final-state backstop.
- **2026-07-15 — Supersede the Milestone 3 immutable `CREATED` snapshot only for new complete-workflow requests.** The same scoped `CREATE_ORDER_V1` key remains bound to its original request hash, while the version-2 canonical fingerprint adds the opaque payment reference and new claims persist an in-progress resource before provider I/O. Historical rows/snapshots remain readable and replayable. Earlier ADR/milestone statements that exclude public orchestration describe their approved historical scope; ADR 0013 and the current normative documents govern Milestone 6 behavior, so those records are not retroactively rewritten.
- **2026-07-15 — Persist each provider operation identity before its external call and recover by lookup.** Authorization identity commits with payment initialization; capture identity commits with the guarded `CAPTURING` transition. Recent active calls suppress competing execution until a configured deadline; stale active or explicit unknown states look up the same ID, and only `NOT_FOUND` permits an equivalent same-ID resend. This extends ADR 0004 without changing its provider port or retry classification.
- **2026-07-15 — Keep the deterministic provider implementation outside the production artifact.** The existing validated integration fixture supplies success, decline, latency, temporary, timeout, lookup, and malformed-response behavior over real HTTP. Production code contains only the provider client/port, fails closed when no trusted base URL is configured, and adds no simulator endpoint, card field, credential, or production dependency.
- **2026-07-11 — Validate OpenAPI without server code generation.** Contract tests enforce conformance without generated framework coupling.
- **2026-07-11 — Contract the mock provider separately and exclude it from the main artifact.** Its external HTTP behavior is validated without exposing simulator controls in production.
- **2026-07-11 — Separate Flyway owner and runtime database roles.** Migration authority does not grant the application DDL or mutation rights over immutable financial/audit data.

## Outcome and follow-up

Current outcome: Milestones 1 through 6 are complete. The authenticated public create command now durably initializes one order/payment identity, authorizes and captures through stable provider operation IDs with lookup-first ambiguity recovery, atomically posts one balanced immutable journal plus one logical outbox event, and finalizes payment/order/idempotency in a separate guarded local transaction. It returns truthful completed, declined, pending, or provider-protocol outcomes without waiting for Kafka. At-least-once publication/consumption, event-ID transport idempotency, and versioned semantic-effect idempotency still create one notification effect under duplicate publication, delivery, or re-enveloping. V008 enforces terminal financial evidence while preserving historical Create Order rows and snapshots.

The repository secret scan and packaged application dependency scan remain clean with no exception mechanism; Kafka 4.3.1 and Prometheus 3.13.1 image scans remain clean. Other official local Compose findings remain visible under exact local-development-only records through 2026-08-13 or compatible fixed-image availability, whichever is earlier; this is not production acceptance. Milestone 7A's prerequisites are now satisfied, but it remains Proposed pending explicit approval; Milestones 7B and 8 also remain Proposed. Quota/retention R3 and authenticated/bounded replay R4 remain separately Proposed production gates. The mock provider is a validated integration fixture rather than a packaged local service or production provider. General payment/outbox/operator recovery is not exposed, so a durable `202`, failed outbox cycle, or replay request still follows the documented inspection/escalation limits. LedgerFlow does not claim a distributed transaction or end-to-end exactly-once delivery.

When all milestones are complete, update this section with:

- delivered behavior and any approved deviations;
- `./gradlew clean verify` evidence;
- AC-001 through AC-016 evidence;
- accepted/superseded ADRs;
- production dependency inventory;
- operational limitations and residual risks; and
- separately proposed follow-ups for real provider integration, notification delivery, refunds/reversals, multi-currency, data retention, deployment, backup/recovery, and performance targets.

Follow-up work is not approved by completing this plan.
