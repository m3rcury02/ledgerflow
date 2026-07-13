# LedgerFlow

LedgerFlow is a Java 25 and Spring Boot 4.1 modular-monolith portfolio project. The current repository contains the verified application foundation only; no business API or financial workflow is implemented yet.

## Prerequisites

- JDK 25
- Docker-compatible runtime for Testcontainers
- GNU Make (optional; every command delegates to the Gradle Wrapper)

Do not install Gradle separately. The committed Gradle 9.6.1 Wrapper is the supported build entry point.

## Verify the repository

```bash
./gradlew --version
./gradlew clean verify
```

The lifecycle checks formatting, static analysis, unit tests, a PostgreSQL Testcontainers context test, Spring Modulith and ArchUnit rules, OpenAPI, and documentation. The equivalent convenience command is `make verify`.

## Run locally

Start a PostgreSQL instance, then provide connection settings without committing credentials:

```bash
export LEDGERFLOW_DB_URL=jdbc:postgresql://localhost:5432/ledgerflow
export LEDGERFLOW_DB_USERNAME=ledgerflow
export LEDGERFLOW_DB_PASSWORD='<local-password>'
export LEDGERFLOW_KAFKA_BOOTSTRAP_SERVERS=localhost:9092
./gradlew :application:bootRun
```

Kafka is configured for later milestones but no producer, consumer, topic, or business operation exists in the scaffolding milestone.

## Project structure

- `application` — the single deployable Spring Boot application and cross-module verification suites.
- `modules/orders` — order feature boundary.
- `modules/payments` — payment feature boundary.
- `modules/ledger` — ledger feature boundary.
- `modules/messaging` — messaging feature boundary.
- `modules/notifications` — notification feature boundary.
- `modules/operations` — operator-recovery feature boundary.

Each feature is a Gradle library under `com.ledgerflow.<feature>`. Application code remains package-by-feature; repository-wide controller, service, repository, entity, and model packages are forbidden.

See [development workflow](docs/development-workflow.md), [architecture](docs/architecture.md), and the [MVP ExecPlan](docs/plans/mvp-execplan.md) for the governing details.
