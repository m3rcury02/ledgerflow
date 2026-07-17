# ADR 0002: Define MVP Module Boundaries and Orchestration

- Status: Accepted
- Date: 2026-07-11
- Accepted: 2026-07-17
- Decision owners: LedgerFlow maintainers

## Context

The MVP crosses order intake, payment-provider I/O, accounting, outbox publication, Kafka consumption, notifications, and operator recovery. These capabilities must remain understandable inside one deployable modular monolith without pretending that each feature is a future microservice.

The successful capture path also needs one PostgreSQL transaction across payment state, order state, ledger posting, and outbox insertion. External provider calls must not keep that transaction open.

## Decision

Create six package-by-feature modules beneath the bootstrap-selected base package:

- `orders` owns orders, create-order idempotency, and workflow coordination;
- `payments` owns payment state, attempts, provider ports, and provider adapters;
- `ledger` owns accounts, ledger transactions, entries, and balance rules;
- `messaging` owns the transactional outbox and Kafka publisher;
- `notifications` owns Kafka inbox and notification records; and
- `operations` owns failed-operation projections, retry requests, and operator audit.

Each module exposes a small `.api` package. Direct access to another module's internals or tables is prohibited.

The normal HTTP request synchronously coordinates authorization, capture, and local finalization. Kafka publication and notification creation are asynchronous.

Provider HTTP calls occur outside PostgreSQL transactions. After capture is confirmed, the coordinator opens one local transaction and invokes payment, order, ledger, and outbox module APIs. Spring transaction propagation joins those calls to the same transaction.

The `payments` module uses hexagonal architecture because the provider is replaceable external I/O and the payment state machine must remain independent of HTTP. Other modules start with a simpler feature-local design. Ports are introduced only for a real boundary, not for every class.

Persistence uses Spring Framework `JdbcClient` and explicit SQL. This keeps guarded state updates, PostgreSQL constraints, and outbox lease queries visible without adding an ORM or Spring Data abstraction.

Architecture tests verify API/internal imports, acyclic module dependencies, table-access ownership conventions, and isolation of payment domain code from Spring HTTP adapters.

## Consequences

### Positive

- One deployable and one database retain simple local transactions.
- Module ownership and cross-module APIs remain explicit and testable.
- Payment-provider substitution and fault testing do not contaminate the domain state machine.
- Explicit SQL makes concurrency and PostgreSQL-specific correctness visible.

### Costs and risks

- The capture-finalization transaction intentionally coordinates several modules.
- Module APIs must avoid becoming generic service locators.
- Table ownership is partly a review convention and requires integration/architecture support.
- Synchronous provider work makes HTTP latency and timeout handling part of the API design.

## Alternatives considered

### Separate microservices

Rejected for the MVP because distributed transactions, service deployment, and network orchestration would obscure the required correctness demonstration.

### Global technical-layer packages

Rejected because they weaken capability ownership and violate repository governance.

### Hexagonal architecture in every module

Rejected as unnecessary ceremony without an external or complex domain boundary.

### JPA/Hibernate

Rejected for the MVP because explicit PostgreSQL locking, guarded updates, deferred triggers, and `SKIP LOCKED` claims are central to the design and do not benefit from an ORM abstraction.
