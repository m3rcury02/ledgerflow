# LedgerFlow Architecture

## Status

This document defines the architectural constraints for LedgerFlow. Milestones 1 and 2 established the application and local infrastructure, Milestone 3 added the Create Order HTTP/persistence slice, Milestone 4 added the non-public payment/provider boundary, Milestone 5A added immutable ledger posting, and Milestone 5B added the transactional outbox, Kafka relay, idempotent notification consumer, dead-letter catalog, and audited command-line replay. Public payment orchestration, order completion, a final payment `CAPTURED` state, and operator HTTP workflows remain future work.

## Architectural goals

LedgerFlow is a single deployable application built as a modular monolith. The design should:

- keep business capabilities independently understandable and testable;
- prevent accidental coupling between capabilities;
- preserve transactional simplicity where it is valuable;
- make external contracts and persistence changes explicit;
- handle money, time, retries, and operational diagnostics safely; and
- allow later extraction of a module only when evidence justifies the cost.

A modular monolith is a logical boundary model, not a commitment to Java Platform Module System modules or future microservices. The approved bootstrap uses Gradle subprojects for compile-time feature boundaries while producing one deployable application.

## Code organization and module boundaries

Application code is organized package-by-feature beneath the base package `com.ledgerflow`.

The Gradle build contains one deployable `application` project and feature-library projects for `orders`, `payments`, `ledger`, `messaging`, `notifications`, and `operations`. These projects strengthen build-time boundaries but do not imply separate runtime processes or databases.

Each top-level feature package is a logical application module. Repository-wide packages such as `controller`, `service`, `repository`, `entity`, or `model` are prohibited.

A feature module should expose cross-module types through a small `<feature>.api` package. Its implementation belongs under `<feature>.internal` or other non-exported feature-local packages.

The dependency rules are:

1. A feature may depend only on another feature's `api` package.
2. A feature must not reference another feature's internal packages, repositories, entities, or database access code.
3. Feature dependency cycles are prohibited.
4. Framework and infrastructure code must not become an alternative path around feature APIs.
5. Shared code must represent a stable, genuinely cross-cutting concept. A generic `common`, `util`, or `shared` dumping ground is prohibited.
6. Every database table has one owning feature. Other features access its data through its API, not through direct repositories or SQL.

These rules are verified by the `architectureTest` task. Exceptions require an accepted ADR and a narrowly scoped automated rule describing the exception.

## Internal feature design

The default is the simplest package-by-feature design that preserves the module boundary. A feature may organize code further by cohesive use case or sub-capability.

Hexagonal architecture is not the default template for every feature. Introduce domain, application, port, and adapter boundaries only when one or more of these conditions applies:

- core rules need to remain independent of Spring or persistence;
- a feature integrates with replaceable external systems;
- multiple inbound or outbound adapters implement the same capability;
- the domain model is complex enough that infrastructure concerns obscure it; or
- isolated testing materially improves risk control.

When hexagonal architecture is used:

- domain code does not depend on Spring, HTTP, persistence, or messaging;
- inbound adapters invoke application use cases;
- outbound adapters implement ports owned by the application or domain side; and
- the reason and resulting dependency direction are recorded in the ExecPlan or an ADR.

Do not introduce interfaces, mapping layers, commands, events, or duplicate models solely to imitate an architectural pattern.

The `orders` slice keeps framework-free money, key, fingerprint, and order types behind one application-owned persistence port. This narrow boundary makes the PostgreSQL idempotency transaction testable without imposing ports on every feature. The HTTP and `JdbcClient` adapters remain module-internal.

The `payments` module uses a hexagonal boundary because provider I/O is replaceable and unreliable, while the payment state machine must remain independently testable. Its application-owned `PaymentProvider` and `PaymentStore` ports isolate a JDK HTTP adapter and guarded JDBC adapter. The workflow itself is deliberately not transactional: each persistence call opens one short transaction, and no provider call or retry delay runs with a database transaction open.

The `ledger` module keeps a framework-independent journal model because balance, immutability, and compensation rules need focused unit tests. Its small `ledger.api` is the only cross-module posting surface. Ledger posting uses the `payments.api` accounting boundary; it does not import payment internals or query payment tables. Spring transactions and JDBC remain internal adapters, and ArchUnit verifies that the ledger domain does not depend on them.

Capture accounting has two directed feature dependencies: `ledger -> payments.api` and `ledger -> messaging.api`. A capture-posting transaction locks the payment, inserts the ledger transaction and entries, marks it `CAPTURE_ACCOUNTED`, and invokes the mandatory-transaction outbox appender. Payments and messaging do not call ledger, so the dependencies remain acyclic. A later coordinator may call the public APIs but must not bypass module internals.

The `messaging.api.OutboxEventAppender` is deliberately narrow: it appends one typed payment-captured event only when a caller already owns a PostgreSQL transaction. The messaging module owns canonical envelope serialization, outbox persistence, leases, retry timing, Kafka publication, and W3C trace-header injection. The notifications module owns validation, inbox deduplication, notification persistence, bounded consumer retry, the DLT catalog, and audited replay. Neither consumer nor replay path may call ledger or mutate financial state.

ADR 0004 accepts one narrow database-boundary exception: the payment-owned `V002` constraint trigger reads only an order's ID, amount, and currency to reject a payment whose copied money differs from its referenced order. Application code still cannot query another module's tables, and a PostgreSQL integration test verifies this invariant. This exception does not authorize general cross-module SQL.

ADR 0005 accepts a second narrow database-integrity exception: `V003` adds the payment-owned `CAPTURE_ACCOUNTED` state constraints, and the ledger's deferred validator reads only the referenced payment's state, order ID, amount, and currency. This is necessary to prevent payment accounting status and its journal from diverging at commit. Ledger application code still uses `payments.api` and never queries the payment table. Any expansion of this trigger contract requires a new ADR review.

## HTTP contracts

The version-controlled OpenAPI document at `application/src/main/openapi/ledgerflow.yaml` is the source of truth for public HTTP APIs. The test-only external provider contract at `application/src/testFixtures/openapi/mock-payment-provider.yaml` is validated separately and is not a public LedgerFlow API.

An HTTP change must:

- update the OpenAPI contract before or with implementation;
- pass `openApiValidate`;
- include tests for affected status codes, media types, validation, and schemas; and
- document compatibility and migration behavior for breaking changes.

Whether server interfaces and models are generated from OpenAPI will be decided during bootstrap after confirming Spring Boot 4.1 and Java 25 tool compatibility. Generated files, if any, must never be edited manually.

## Persistence

Production persistence uses PostgreSQL. Integration tests use a PostgreSQL Testcontainer compatible with the production major version. H2 is prohibited because its SQL and transactional behavior do not provide adequate PostgreSQL compatibility.

Flyway migrations live under `application/src/main/resources/db/migration` and are the only supported mechanism for production schema changes.

Migration rules:

- migrations are append-only after merge;
- a correction to a merged migration is a new forward migration;
- application changes and required migrations are delivered together;
- integration tests start from an empty PostgreSQL database and apply every migration;
- destructive or long-running migrations require an ExecPlan with compatibility, recovery, and rollout details; and
- a module accesses only tables it owns unless an accepted ADR defines a controlled exception.

The current local, production-design, and integration-test baseline is PostgreSQL 18. Migrations use ordered `VNNN__description.sql` names. `V001` owns orders and HTTP idempotency; `V002` owns payments and append-only payment attempt history; `V003` owns ledger accounts, journal transactions, entries, deferred balance checks, and `CAPTURE_ACCOUNTED`; `V004` owns the transactional outbox; and `V005` owns the notification inbox, notification records, dead-letter catalog, and append-only replay audit.

### Capture-accounting transaction and isolation boundary

Payment-provider I/O completes before accounting starts. `LedgerPosting.postPaymentCapture` then opens one PostgreSQL `READ COMMITTED` transaction and performs this sequence:

1. lock the payment row through `payments.api` using `SELECT ... FOR UPDATE`;
2. accept `CAPTURE_CONFIRMED`, or verify and replay an existing `CAPTURE_ACCOUNTED` journal;
3. insert one journal header and all entries;
4. transition the locked payment to `CAPTURE_ACCOUNTED` with its expected version;
5. append one immutable, deduplicated payment-captured outbox row through `messaging.api`; and
6. let deferred ledger constraints validate the complete journal before commit.

The payment row is the same-payment serialization point. Under `READ COMMITTED`, a concurrent writer waits, then observes `CAPTURE_ACCOUNTED` and verifies the existing journal and outbox event. Unique journal and outbox deduplication keys prevent duplicates even if a future caller fails to follow the lock convention. A constraint, state, or outbox failure rolls back the payment transition, journal, entries, and event together. Order state is unchanged: this transaction does not imply order `COMPLETED` or payment `CAPTURED`. This reasoning does not cover future cross-payment or account-period invariants; such behavior must reassess isolation in an ADR.

Posted ledger rows reject update and delete. Corrections append an exact reversing transaction linked to the original rather than mutating history. Production privileges must prevent the application role from disabling triggers or applying DDL; the current local/test owner credential is a development convenience, not the production authorization model.

### Outbox publication and notification consumption

The dedicated publisher uses short owner-token lease transactions. Each instance claims due or expired rows with `SELECT ... FOR UPDATE SKIP LOCKED`, commits the claim, publishes outside PostgreSQL, waits for `acks=all`, and then marks the row `PUBLISHED` only through an owner-guarded update. Multiple instances can work concurrently. A send may therefore be duplicated if the process stops after broker acknowledgement but before the marker commits.

Publication has at most 10 attempts per cycle with exponential backoff capped at 256 seconds and configurable jitter. Failed rows remain durable and inspectable; this milestone does not expose an outbox retry API. The default main topic is `ledgerflow.payment-captured.v1`, keyed by order ID.

The notification listener validates the exact envelope, key, type, version, money, and identity relationships. It makes one initial attempt plus three bounded blocking retries for transient failures. A poison record is published to `ledgerflow.payment-captured.v1.dlt` and acknowledged before the source offset advances. Inbox event-ID/hash deduplication and the unique notification event ID make matching redelivery a successful no-op; the same event ID with different canonical content is an integrity failure.

The DLT listener catalogs bounded safe evidence. Malformed raw bytes are not copied into PostgreSQL. The narrow command-line replay accepts only catalog rows already marked replayable, records actor/reason/correlation audit events, preserves the canonical envelope and Kafka key, removes old exception/delivery metadata, and injects new transport correlation and trace headers. There is no general Kafka resend command and no operator HTTP workflow in this milestone.

These boundaries provide atomic PostgreSQL business data plus outbox, at-least-once publication, and at-least-once consumption. They provide exactly one logical notification database side effect for a stable event ID and content; they do not provide end-to-end exactly-once delivery.

## Money and time

A monetary amount consists of:

- a signed 64-bit integer count of minor units; and
- an uppercase three-letter ISO 4217 currency code.

Java uses a dedicated money value type backed by `long`; HTTP schemas use an `int64` amount and currency code; PostgreSQL uses an integer-compatible exact type. Monetary arithmetic must check overflow and define rounding explicitly where conversion or allocation occurs. `float` and `double` are prohibited for monetary storage, transport, and calculation.

Persisted points in time use `Instant` and PostgreSQL `timestamptz`. Services, tests, and serialization operate in UTC. Calendar dates, local times, durations, and accounting periods may use more appropriate types when they are not instants. A clock is injected where business behavior depends on the current time.

## Idempotent writes

Any write operation expected to be retried by an external client, webhook sender, scheduler, or message transport must define its idempotency behavior in its contract.

For HTTP operations requiring idempotency:

- the OpenAPI operation declares an `Idempotency-Key` header;
- the key is scoped to the operation and authenticated caller where applicable;
- the request fingerprint and outcome are persisted atomically with the business change;
- repeating the same key and equivalent request returns the original outcome;
- reusing the same key with a different request is rejected as a conflict;
- concurrent requests with the same key cannot apply the write twice; and
- retention and replay behavior are documented for the operation.

Integration tests must cover replay, mismatched reuse, concurrency, and failure recovery.

## Observability and secrets

Production logs are structured records rather than free-form concatenated text. Every inbound request accepts a valid `X-Correlation-Id` or creates one, returns it in the response, includes it in logs, and propagates it to supported outbound calls.

Outbox rows persist the originating correlation ID and validated W3C `traceparent`/`tracestate`. Kafka publication creates a producer span and injects current W3C trace headers; listeners extract those headers before processing. A DLT replay starts independent transport correlation and trace context while retaining the envelope's original business correlation. Operator-request and stored-origin span linking remains future work.

Untrusted correlation IDs must be length- and character-limited before logging. Logs must not contain credentials, tokens, secret configuration, full financial payloads, or unnecessary personal data.

Secrets are supplied through environment variables or an approved secret-management system. Source code, configuration, fixtures, documentation, and example files contain placeholders only. Secret scanning should run in CI.

## Automated architecture verification

The Gradle `architectureTest` task must verify at least:

- feature modules do not access another feature's internals;
- module dependencies are acyclic;
- repository-wide technical-layer packages are absent;
- domain packages declared as hexagonal do not depend on Spring or adapters;
- forbidden monetary floating-point types are not used;
- persistence code does not introduce H2; and
- documented exceptions correspond to explicit test rules.

Spring Modulith verifies logical application modules, API/internal access, and cycles. ArchUnit complements it with repository-specific package rules. Both run through `architectureTest`.

## Local development infrastructure

`compose.yaml` provides pinned local dependencies for development and demonstrations. It is not production topology or authorization guidance. Services bind only to `127.0.0.1`, have bounded laptop-oriented resources and health checks, and are operated through `scripts/dev-*`.

Keycloak stores its data in a separate database on the local PostgreSQL instance; embedded H2 is not used. Valkey is an ephemeral Redis-compatible cache service and is not an approved application datastore. Local Kafka is a single combined broker/controller in KRaft mode. Prometheus, Grafana, Tempo, Loki, and OpenTelemetry Collector provide a self-contained observability path without committing credentials or choosing a production observability vendor.

The Create Order slice accepts ADR 0003's scoped idempotency decision and validates JWTs against an issuer/JWK configuration. The non-public payment harness accepts ADR 0004 and exercises a separate deterministic provider fixture over HTTP; no public route invokes it. Capture accounting accepts ADR 0005, and the implemented outbox/Kafka behavior accepts ADRs 0006 and 0007 within their documented scope. Production identity, real payment provider, broker security, cache use, persistence roles, deployment topology, TLS, retention, backup, and sizing remain subject to approved implementation or deployment milestones.

## Decisions intentionally deferred

The following require product or operational evidence and are not selected by this document:

- public API versioning policy;
- OpenAPI code generation;
- deployment platform and topology;
- caches, search engines, or additional datastores;
- extraction into independently deployed services.

These decisions require an approved milestone and, when significant, an ADR.
