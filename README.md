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

## Local dependency environment

Start all local dependencies and wait until Compose reports all nine services healthy:

```bash
./scripts/dev-up
./scripts/dev-status
```

The scripts use an ignored `.env` when present and otherwise use the non-secret placeholders in `.env.example`. Never put production credentials in either file. `dev-down` stops containers while preserving useful named volumes; `dev-reset` deletes all local infrastructure data and immediately recreates the environment.

The per-service hard ceilings total 7.75 CPUs and 5 GiB of memory; these are limits, not reservations. Allocate at least 4 CPUs and 6 GiB to the container runtime for reliable startup. The first Keycloak start can take several minutes while it initializes its PostgreSQL schema.

```bash
./scripts/dev-down
./scripts/dev-reset  # destructive: deletes PostgreSQL, Kafka, and observability data
```

All host bindings use `127.0.0.1`. Every exposed port is listed below; change its corresponding variable in `.env` if the default conflicts with another local process.

| Service | Variable | Default | Purpose |
| --- | --- | ---: | --- |
| PostgreSQL | `POSTGRES_PORT` | 5432 | LedgerFlow and Keycloak databases |
| Kafka | `KAFKA_PORT` | 9092 | External Kafka 4.3 KRaft listener |
| Valkey | `VALKEY_PORT` | 6379 | Redis-compatible ephemeral cache |
| Keycloak | `KEYCLOAK_PORT` | 8081 | Identity and realm endpoints |
| OpenTelemetry Collector gRPC | `OTEL_GRPC_PORT` | 4317 | OTLP/gRPC ingestion |
| OpenTelemetry Collector HTTP | `OTEL_HTTP_PORT` | 4318 | OTLP/HTTP ingestion |
| Prometheus | `PROMETHEUS_PORT` | 9090 | Metrics queries and UI |
| Grafana | `GRAFANA_PORT` | 3000 | Provisioned observability UI |
| Tempo | `TEMPO_PORT` | 3200 | Trace query/readiness API |
| Loki | `LOKI_PORT` | 3100 | Log query/readiness API |

Useful readiness checks with the default environment are:

```bash
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example exec -T postgres pg_isready -U ledgerflow -d ledgerflow
docker compose --env-file .env.example exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list
docker compose --env-file .env.example exec -T valkey valkey-cli ping
curl --fail http://localhost:8081/realms/ledgerflow/.well-known/openid-configuration
curl --fail http://localhost:9090/-/ready
curl --fail http://localhost:3000/api/health
curl --fail http://localhost:3200/ready
curl --fail http://localhost:3100/ready
```

Grafana starts with Prometheus, Tempo, and Loki data sources already provisioned. Keycloak imports the `ledgerflow` realm with `customer` and `operator` roles but no local user credentials. Kafka runs one combined broker/controller in KRaft mode; ZooKeeper is not present. These settings are intentionally local-only and are not a production deployment design.

## Run the application locally

After starting the dependencies, map local PostgreSQL configuration into the application without committing credentials:

```bash
export LEDGERFLOW_DB_URL=jdbc:postgresql://localhost:5432/ledgerflow
export LEDGERFLOW_DB_USERNAME=ledgerflow
export LEDGERFLOW_DB_PASSWORD='<local-password>'
export LEDGERFLOW_KAFKA_BOOTSTRAP_SERVERS=localhost:9092
./gradlew :application:bootRun
```

Kafka and the other services are available for later milestones, but no producer, consumer, cache integration, identity behavior, topic, or business operation exists yet.

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
