# LedgerFlow Architecture

## Status

This document defines the initial architectural constraints for LedgerFlow. The application has not yet been scaffolded, so it distinguishes committed constraints from decisions that must wait for concrete product requirements.

## Architectural goals

LedgerFlow is a single deployable application built as a modular monolith. The design should:

- keep business capabilities independently understandable and testable;
- prevent accidental coupling between capabilities;
- preserve transactional simplicity where it is valuable;
- make external contracts and persistence changes explicit;
- handle money, time, retries, and operational diagnostics safely; and
- allow later extraction of a module only when evidence justifies the cost.

A modular monolith is a logical boundary model, not a commitment to Java Platform Module System modules, Gradle subprojects, or future microservices.

## Code organization and module boundaries

Application code is organized package-by-feature beneath a single base package chosen during the bootstrap milestone.

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

## HTTP contracts

The version-controlled OpenAPI document at `src/main/openapi/ledgerflow.yaml` is the source of truth for HTTP APIs.

An HTTP change must:

- update the OpenAPI contract before or with implementation;
- pass `openApiValidate`;
- include tests for affected status codes, media types, validation, and schemas; and
- document compatibility and migration behavior for breaking changes.

Whether server interfaces and models are generated from OpenAPI will be decided during bootstrap after confirming Spring Boot 4.1 and Java 25 tool compatibility. Generated files, if any, must never be edited manually.

## Persistence

Production persistence uses PostgreSQL. Integration tests use a PostgreSQL Testcontainer compatible with the production major version. H2 is prohibited because its SQL and transactional behavior do not provide adequate PostgreSQL compatibility.

Flyway migrations live under `src/main/resources/db/migration` and are the only supported mechanism for production schema changes.

Migration rules:

- migrations are append-only after merge;
- a correction to a merged migration is a new forward migration;
- application changes and required migrations are delivered together;
- integration tests start from an empty PostgreSQL database and apply every migration;
- destructive or long-running migrations require an ExecPlan with compatibility, recovery, and rollout details; and
- a module accesses only tables it owns unless an accepted ADR defines a controlled exception.

The production PostgreSQL major version and migration version-numbering convention will be selected in the bootstrap or deployment milestone and then pinned.

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

The initial implementation may use ArchUnit as a test-only dependency. If another mechanism is chosen, it must provide equivalent failing build checks and the choice must be documented.

## Decisions intentionally deferred

The following require product or operational evidence and are not selected by this document:

- concrete business modules and their APIs;
- the base Java package;
- authentication and authorization;
- public API versioning policy;
- OpenAPI code generation;
- PostgreSQL major version;
- deployment platform and topology;
- asynchronous messaging or an event broker;
- caches, search engines, or additional datastores;
- Gradle single-project versus multi-project structure; and
- extraction into independently deployed services.

These decisions require an approved milestone and, when significant, an ADR.
