# LedgerFlow Architecture

## Status

This document defines the architectural constraints for LedgerFlow. Milestones 1 and 2 established the application and local infrastructure; Milestone 3 adds only the Create Order HTTP/persistence slice. Payments, financial posting, and messaging remain unimplemented.

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

## HTTP contracts

The version-controlled OpenAPI document at `application/src/main/openapi/ledgerflow.yaml` is the source of truth for HTTP APIs.

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

The current local, production-design, and integration-test baseline is PostgreSQL 18. Migrations use ordered `VNNN__description.sql` names. The first migration owns only orders and HTTP idempotency.

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

Selecting these development containers does not authorize payment or messaging integration. The Create Order slice now accepts ADR 0003's scoped idempotency decision and validates JWTs against an issuer/JWK configuration. Production identity, broker, cache, observability, persistence roles, deployment topology, TLS, retention, backup, and sizing remain subject to approved implementation or deployment milestones.

## Decisions intentionally deferred

The following require product or operational evidence and are not selected by this document:

- public API versioning policy;
- OpenAPI code generation;
- deployment platform and topology;
- asynchronous messaging or an event broker;
- caches, search engines, or additional datastores;
- extraction into independently deployed services.

These decisions require an approved milestone and, when significant, an ADR.
