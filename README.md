# LedgerFlow

LedgerFlow is a Java 25 and Spring Boot 4.1 modular-monolith portfolio project. Its public vertical slice exposes contract-first, JWT-secured create/read order APIs with durable PostgreSQL idempotency. Non-public payment and capture-accounting slices implement explicit provider states, safe timeout reconciliation, immutable balanced journals, a transactional outbox, at-least-once Kafka publication/consumption, idempotent notification persistence, and audited dead-letter replay. Public payment orchestration, final order completion, and an operator HTTP workflow are not implemented yet.

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

The lifecycle checks formatting, static analysis, unit tests, PostgreSQL Testcontainers repository/HTTP/concurrency tests, Spring Modulith and ArchUnit rules, OpenAPI, and documentation. The equivalent convenience command is `make verify`.

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
export LEDGERFLOW_OAUTH2_AUDIENCE=ledgerflow-api
export LEDGERFLOW_OAUTH2_ISSUER=http://localhost:8081/realms/ledgerflow
export LEDGERFLOW_OAUTH2_JWK_SET_URI=http://localhost:8081/realms/ledgerflow/protocol/openid-connect/certs
./gradlew :application:bootRun
```

Flyway applies `V001__create_orders_and_idempotency.sql` through `V005__create_notification_inbox_and_dead_letters.sql` at startup. The Kafka publisher and notification/DLT consumers are disabled by default; enable them explicitly with `LEDGERFLOW_OUTBOX_PUBLISHER_ENABLED`, `LEDGERFLOW_NOTIFICATION_CONSUMER_ENABLED`, and `LEDGERFLOW_NOTIFICATION_DLT_CONSUMER_ENABLED`. No cache integration or public payment/ledger route exists.

## Create Order API

The imported local realm intentionally contains roles but no users or credentials. Supply a JWT from your configured development issuer whose `sub` identifies the customer and whose scope contains `ledgerflow.orders.write` or `ledgerflow.orders.read` as appropriate.

Create one positive INR order:

```bash
curl --fail-with-body http://localhost:8080/api/v1/orders \
  --request POST \
  --header "Authorization: Bearer ${LEDGERFLOW_ACCESS_TOKEN}" \
  --header 'Content-Type: application/json' \
  --header 'Idempotency-Key: checkout-order-0001' \
  --header 'X-Correlation-Id: checkout-demo-001' \
  --data '{"clientReference":"checkout-0001","amount":{"amountMinor":259900,"currency":"INR"}}'
```

Repeat that request with the same key and body to receive the original `201` result and `Idempotency-Replayed: true`. Changing a canonical request field while retaining the key returns `409`. Read the owned order with:

```bash
curl --fail-with-body "http://localhost:8080/api/v1/orders/${ORDER_ID}" \
  --header "Authorization: Bearer ${LEDGERFLOW_ACCESS_TOKEN}" \
  --header 'X-Correlation-Id: checkout-read-001'
```

The complete schemas, examples, validation rules, problem details, and status codes live in `application/src/main/openapi/ledgerflow.yaml` and [the API design](docs/api-design.md).

## Payment provider test harness

Payment authorization and capture are intentionally not connected to the public order routes yet, and neither order `COMPLETED` nor a final payment `CAPTURED` state is implemented. Integration tests start a deterministic external HTTP fixture from `application/src/integrationTest`, validate its separate contract, and cover success, decline, temporary failure, timeout-after-processing, slow response, invalid response, crash recovery, and concurrent transitions.

Provider connect/read/overall deadlines, bounded retry, circuit breaker, and zero-queue concurrency bulkhead use `LEDGERFLOW_PAYMENT_PROVIDER_*` configuration. The default application has no provider base URL, so no provider client/workflow bean starts accidentally. See [the payment recovery runbook](docs/runbook.md) for state interpretation and safe recovery constraints.

## Capture accounting and Kafka slice

The internal ledger use case posts an already `CAPTURE_CONFIRMED` payment once. One `READ COMMITTED` transaction locks the payment, inserts a clearing debit and merchant-payable credit in INR minor units, transitions it to `CAPTURE_ACCOUNTED`, and appends the version-1 payment-captured outbox event through `messaging.api`. Deferred PostgreSQL constraints reject incomplete, unbalanced, or mismatched journals at commit; repeated and concurrent posting returns the original journal and event identity. Posted rows cannot be updated or deleted, and corrections append an exact compensating transaction.

A dedicated publisher leases rows with `SELECT ... FOR UPDATE SKIP LOCKED`, sends outside a database transaction, and marks them published only after Kafka acknowledgement. The notification listener bounds concurrency and poll intake, uses one initial attempt plus three pause-based retries, then publishes poison records to `ledgerflow.payment-captured.v1.dlt` after broker acknowledgement. Inbox event-ID/hash checks make redelivery a no-op and preserve exactly one logical notification database effect. This is at-least-once delivery, not end-to-end exactly-once delivery.

The default topics are `ledgerflow.payment-captured.v1` and `ledgerflow.payment-captured.v1.dlt`. With the normal database/Kafka environment configured, replay one validated catalog row with:

```bash
scripts/replay-dead-letter '<dead-letter-uuid>' '<actor>' '<specific reason of at least 10 characters>'
```

The narrow command runs the application in non-web mode with listeners and the outbox publisher disabled. It preserves the original envelope and Kafka key while creating new transport correlation and trace context; see [the runbook](docs/runbook.md). This slice deliberately adds no HTTP endpoint, provider call, final order transition, or operator HTTP API. Use [the read-only ledger SQL](docs/sql/ledger-queries.sql) for account balances and payment transaction history.

## Project structure

- `application` — the single deployable Spring Boot application and cross-module verification suites.
- `modules/orders` — order feature boundary.
- `modules/payments` — payment feature boundary.
- `modules/ledger` — ledger feature boundary.
- `modules/messaging` — messaging feature boundary.
- `modules/notifications` — notification feature boundary.
- `modules/operations` — operational health, dependency validation, graceful drain, and future operator-recovery boundary.

Each feature is a Gradle library under `com.ledgerflow.<feature>`. Application code remains package-by-feature; repository-wide controller, service, repository, entity, and model packages are forbidden.

See [development workflow](docs/development-workflow.md), [architecture](docs/architecture.md), and the [MVP ExecPlan](docs/plans/mvp-execplan.md) for the governing details.
