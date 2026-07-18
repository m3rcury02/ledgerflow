# LedgerFlow

LedgerFlow is a Java 25 and Spring Boot 4.1 modular-monolith portfolio project. Its contract-first, JWT-secured public workflow creates an order, safely authorizes/captures through an external mock provider, posts one immutable balanced journal and transactional outbox event, finalizes `CAPTURED`/`COMPLETED`, then publishes/consumes through Kafka asynchronously with transport and semantic notification idempotency. Secured operator APIs provide sanitized failure inspection and idempotent, leased, audited recovery without direct data mutation.

## Technical interview quick start

The fastest reproducible proof is:

```bash
make smoke-test
```

It uses PostgreSQL and Kafka Testcontainers to exercise authenticated HTTP, exact replay and
conflict, provider authorization/capture, balanced ledger posting, transactional outbox,
at-least-once publication/consumption, and exactly one semantic notification effect. No shared
local data or credentials are required. `make demo-mvp` adds the focused decline, timeout, crash,
rollback, DLT, operator-recovery, telemetry-outage, and shutdown matrix. The complete gate remains
`./gradlew --no-daemon clean verify --console=plain`.

| What to inspect | Why it matters |
| --- | --- |
| [MVP evidence](docs/mvp-evidence.md) | Maps every required scenario and AC-001–AC-016 to named automated proof. |
| [Architecture](docs/architecture.md) | Shows module ownership, transaction boundaries, and the complete sequence. |
| [OpenAPI contract](application/src/main/openapi/ledgerflow.yaml) | Defines customer and secured operator HTTP behavior before implementation. |
| [Data model](docs/data-model.md) | Explains UUIDv7 identities, guarded states, immutable ledger, outbox/inbox, and audit constraints. |
| [Threat model](docs/threat-model.md) | Covers BOLA, replay, strict input, secrets, Kafka, telemetry, and privileged recovery. |
| [Whole-repository review](docs/mvp-review.md) | Records release findings, fixes, evidence reviewed, and deliberately limited claims. |

This release is a production-engineering demonstration, not a production deployment. It does not
claim scale, high availability, regulatory certification, real payment integration, or
end-to-end exactly-once delivery. See [operational limitations](docs/operational-limitations.md)
and the [residual-risk register](docs/security/mvp-residual-risk-register.md).

## Prerequisites

- JDK 25
- Docker-compatible runtime for Testcontainers
- `jq` for exact vulnerability-policy evaluation
- GNU Make (optional; every command delegates to the Gradle Wrapper)

Do not install Gradle separately. The committed Gradle 9.6.1 Wrapper is the supported build entry point.

## Verify the repository

```bash
./gradlew --version
./gradlew clean verify
```

The lifecycle checks formatting, static analysis, unit tests, PostgreSQL Testcontainers repository/HTTP/concurrency tests, Spring Modulith and ArchUnit rules, OpenAPI, and documentation. The equivalent convenience command is `make verify`.

Focused release proof commands are:

```bash
make smoke-test
make demo-mvp
```

Security-sensitive, dependency, or container-image changes also run the separate Docker-backed supply-chain check:

```bash
./scripts/security-scan
```

The command builds the application artifact, scans repository configuration and committed content for secrets, scans packaged Java dependencies, and scans every Compose image. Repository-secret and application-artifact findings always fail. Compose findings fail unless they exactly match an unexpired, digest-bound local-development record in the [container risk register](docs/security/local-development-container-risk-register.md); Trivy still prints every finding. These exceptions are prohibited for production. The command uses a version-and-digest-pinned Trivy container and read-only Docker-socket access for image inspection; run it only on a trusted development or CI host.

## Continuous integration

Every pull request runs [`.github/workflows/ci.yml`](.github/workflows/ci.yml) (the full `./gradlew clean verify` lifecycle, then an OCI image build with a CycloneDX SBOM and a Trivy vulnerability scan) and [`.github/workflows/codeql.yml`](.github/workflows/codeql.yml) (CodeQL static analysis). `.github/workflows/security-scan.yml` runs the Docker-socket-privileged `scripts/security-scan` on pushes to `main` and on a schedule, not on pull requests, so a fork-originated PR is never granted that access. Every third-party GitHub Action is pinned to a full commit SHA; [`.github/dependabot.yml`](.github/dependabot.yml) keeps those pins, Gradle dependencies, and the Dockerfile base images current. Recommended `main` branch-protection settings are documented in [`docs/branch-protection.md`](docs/branch-protection.md) — they are recommendations only and are not applied automatically.

## Container image

`Dockerfile` builds a digest-pinned, multi-stage, non-root, read-only-root-filesystem-
compatible image (`docker build --tag ledgerflow:local .`). `./scripts/scan-image` (or
`make image-scan`) builds it and generates a CycloneDX SBOM plus a Trivy vulnerability scan
locally — the same checks `.github/workflows/ci.yml`'s image job runs, for use outside CI.
There is one image for both roles: a "worker" deployment is the identical image started
with `LEDGERFLOW_RECOVERY_WORKER_ENABLED=true` (the default) and, in Kubernetes, simply not
targeted by the public Service. Every hardening property — read-only root filesystem,
graceful shutdown, a byte-for-byte reproducible jar, and the base-image package trimming
that closed 5 real HIGH-severity CVEs by removing unused font-rendering and PKCS#11
packages — is verified with real commands in [`docs/container-hardening.md`](docs/container-hardening.md).

## Kubernetes deployment (local)

`deploy/helm/ledgerflow/` is a Helm chart deploying `ledgerflow:local` as two
role-differentiated Deployments — `ledgerflow-api` (request/response, autoscaled 2–5 replicas
via an HPA) and `ledgerflow-worker` (background outbox/notification/recovery processing, no
public Service, no ingress traffic permitted by its NetworkPolicy) — with security contexts
matching [container hardening](docs/container-hardening.md) (non-root, read-only root
filesystem, dropped capabilities), resource limits, PodDisruptionBudgets, and least-privilege
ServiceAccounts. `deploy/kind/` stands up a self-contained local `kind` cluster, including
dev-only PostgreSQL/Kafka/Keycloak dependencies, with one command:

```bash
scripts/kind-up            # create/reuse the cluster, build+load images, deploy everything
scripts/kind-smoke-test    # real order creation, worker-independence, and NetworkPolicy proof
scripts/kind-down          # delete the cluster
```

Full architecture, a real HPA scale-out under load, and every issue found and fixed while
actually deploying (not just inspecting manifests) are recorded in
[`docs/kubernetes-deployment.md`](docs/kubernetes-deployment.md).

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
docker compose --env-file .env.example exec -T kafka nc -z 127.0.0.1 29092
docker compose --env-file .env.example exec -T valkey valkey-cli ping
curl --fail http://localhost:8081/realms/ledgerflow/.well-known/openid-configuration
curl --fail http://localhost:9090/-/ready
curl --fail http://localhost:3000/api/health
curl --fail http://localhost:3200/ready
curl --fail http://localhost:3100/ready
```

Grafana starts with Prometheus, Tempo, and Loki data sources already provisioned. Keycloak imports the `ledgerflow` realm with `customer`, `operator`, and `admin` roles, reusable order/operation scopes, and a `ledgerflow-api` audience mapper, but no users, passwords, or client secrets. Kafka uses Apache's official 4.3.1 native image and runs one combined broker/controller in KRaft mode; ZooKeeper is not present. These settings are intentionally local-only and are not a production deployment design.

Keycloak imports a realm file only when that realm does not already exist. After a committed realm-definition change, an existing local PostgreSQL volume retains its prior realm; review the destructive warning and run `./scripts/dev-reset` only when it is safe to discard all local infrastructure data. A fresh-volume validation must be used when preserving existing local data.

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
export LEDGERFLOW_PAYMENT_PROVIDER_BASE_URL=http://127.0.0.1:8090
export LEDGERFLOW_MANAGEMENT_PORT=8082
export LEDGERFLOW_HEALTH_PROBE_CACHE_TTL=2s
export LEDGERFLOW_DEPLOYMENT_ENVIRONMENT=local
export LEDGERFLOW_TRACE_SAMPLING_PROBABILITY=1.0
export LEDGERFLOW_OTLP_TRACES_ENDPOINT=http://localhost:4318/v1/traces
export LEDGERFLOW_OTLP_LOGS_ENDPOINT=http://localhost:4318/v1/logs
./gradlew :application:bootRun
```

Flyway applies V001 through `V009__secure_operator_recovery.sql` at startup. The Kafka publisher and notification/DLT consumers are disabled by default; enable them explicitly with `LEDGERFLOW_OUTBOX_PUBLISHER_ENABLED`, `LEDGERFLOW_NOTIFICATION_CONSUMER_ENABLED`, and `LEDGERFLOW_NOTIFICATION_DLT_CONSUMER_ENABLED`. The database-backed recovery worker defaults on and can be disabled with `LEDGERFLOW_RECOVERY_WORKER_ENABLED=false`. No cache integration or direct public payment/ledger route exists.

Actuator is not served on application port `8080`. With the local override above, status-only liveness/readiness and Prometheus are available on management port `8082`; aggregate health, details, components, and `info` are unavailable. The management listener must never be public. Production ingress, network-policy, and Kafka ACL requirements are defined in [deployment security](docs/deployment-security.md).

```bash
curl --fail http://localhost:8082/actuator/health/liveness
curl --fail http://localhost:8082/actuator/health/readiness
curl --fail http://localhost:8082/actuator/prometheus
```

## Create Order API

The imported local realm intentionally contains roles and scopes but no users or credentials. Provision local identities outside version control. Supply a JWT from the configured issuer with audience `ledgerflow-api`, an allowlisted `customer` or `admin` realm role, and `ledgerflow.orders.write` or `ledgerflow.orders.read` scope as appropriate. `operator` alone cannot access a customer's order.

The API rejects unknown or duplicate JSON properties, query parameters on create, compressed request bodies, unsupported media types, and create-order bodies larger than 16 KiB by default. Each application instance permits 60 create attempts per authenticated subject per minute by default; the response supplies `Retry-After` when the limit is reached. These tunable defenses use `LEDGERFLOW_MAX_WRITE_PAYLOAD`, `LEDGERFLOW_WRITE_RATE_LIMIT_REQUESTS`, `LEDGERFLOW_WRITE_RATE_LIMIT_WINDOW`, and `LEDGERFLOW_WRITE_RATE_LIMIT_MAX_PRINCIPALS`. A production ingress must still enforce aggregate multi-instance and unauthenticated volumetric limits.

Create one positive INR order:

```bash
curl --fail-with-body http://localhost:8080/api/v1/orders \
  --request POST \
  --header "Authorization: Bearer ${LEDGERFLOW_ACCESS_TOKEN}" \
  --header 'Content-Type: application/json' \
  --header 'Idempotency-Key: checkout-order-0001' \
  --header 'X-Correlation-Id: checkout-demo-001' \
  --data '{"clientReference":"checkout-0001","amount":{"amountMinor":259900,"currency":"INR"},"paymentMethodReference":"pm_mock_success"}'
```

On success the response is `201` with order `COMPLETED` and payment `CAPTURED`; this means the balanced journal and outbox event are durable, not that Kafka or notification processing completed. Repeat the same key/body for the original result plus `Idempotency-Replayed: true`. Changing any canonical field, including the opaque payment reference, returns `409`. Read the owned order with:

```bash
curl --fail-with-body "http://localhost:8080/api/v1/orders/${ORDER_ID}" \
  --header "Authorization: Bearer ${LEDGERFLOW_ACCESS_TOKEN}" \
  --header 'X-Correlation-Id: checkout-read-001'
```

The complete schemas, examples, validation rules, problem details, and status codes live in `application/src/main/openapi/ledgerflow.yaml` and [the API design](docs/api-design.md).

## Payment provider test harness

Public creation is connected to the payment workflow. Integration tests start a deterministic external HTTP fixture, validate its separate contract, and cover success, both declines, temporary failure, timeout-confirmed lookup, timeout/`NOT_FOUND` same-ID resend, slow response, invalid response, crash recovery, and concurrency. The fixture is test-only and is not packaged in `ledgerflow.jar`; manual curl use requires a separately running implementation of `application/src/testFixtures/openapi/mock-payment-provider.yaml` at `LEDGERFLOW_PAYMENT_PROVIDER_BASE_URL`. If the origin is unset, create fails safely with `503` and its initialization transaction rolls back.

Provider connect/read/overall/active-operation deadlines, bounded retry, circuit breaker, and zero-queue concurrency bulkhead use `LEDGERFLOW_PAYMENT_PROVIDER_*`. The default application has no provider base URL, so no outbound provider client starts accidentally. See [the payment recovery runbook](docs/runbook.md).

## Capture accounting and Kafka slice

One `READ_COMMITTED` transaction locks a `CAPTURE_CONFIRMED` payment, inserts the clearing debit and merchant-payable credit, transitions it to `CAPTURE_ACCOUNTED`, and appends the version-1 payment-captured outbox event. A separate short transaction finalizes payment/order/idempotency. V008 deferred checks reject `COMPLETED` without the captured payment, journal, and outbox evidence. Repeated/concurrent execution returns the original identities. Posted rows are immutable; corrections append an exact compensating transaction.

A dedicated publisher leases rows with `SELECT ... FOR UPDATE SKIP LOCKED`, sends outside a database transaction, and marks them published only after Kafka acknowledgement. The notification listener bounds concurrency and poll intake, uses one initial attempt plus three pause-based retries, then publishes poison records to `ledgerflow.payment-captured.v1.dlt` after broker acknowledgement. Inbox event-ID/hash checks make matching envelope redelivery a transport no-op. A separate database-unique identity based on the immutable capture ledger transaction prevents a new event ID from repeating the same notification business effect and detects conflicting content. Terminal invalid DLT input is acknowledged only after immutable sanitized evidence using actual DLT coordinates commits. This is at-least-once delivery, not end-to-end exactly-once delivery.

The default topics are `ledgerflow.payment-captured.v1` and `ledgerflow.payment-captured.v1.dlt`. With the normal database/Kafka environment configured, replay one validated catalog row with:

```bash
export LEDGERFLOW_OPERATOR_ACCESS_TOKEN='<operator-token-from-local-keycloak>'
scripts/replay-dead-letter \
  '<dead-letter-uuid>' \
  '<unique-idempotency-key>' \
  '<specific reason of at least 10 characters>'
```

The script calls the secured operator API. Actor identity comes from the validated JWT, inspection and retry scopes remain separate, and the durable worker preserves the original envelope, event identity, and Kafka key while replacing retry-only transport metadata. See [the runbook](docs/runbook.md). Use [the read-only ledger SQL](docs/sql/ledger-queries.sql) for balances and payment history.

## Observability demonstration

Prometheus scrapes only `http://host.docker.internal:8082/actuator/prometheus`; port 8080 is never a metrics target. The Collector exports bounded traces to Tempo and structured logs to Loki. Grafana provisions five version-controlled dashboards plus Prometheus-to-Tempo exemplars, Tempo-to-Loki trace links, and Loki-to-Tempo trace-ID links without UI edits.

Validate the complete observability configuration with the exact pinned service images:

```bash
make observability-check
```

For a complete asynchronous trace, enable the publisher and both consumers before starting the application, run a local implementation of the test provider contract, and supply a valid customer token without writing it to disk:

```bash
export LEDGERFLOW_OUTBOX_PUBLISHER_ENABLED=true
export LEDGERFLOW_NOTIFICATION_CONSUMER_ENABLED=true
export LEDGERFLOW_NOTIFICATION_DLT_CONSUMER_ENABLED=true
export LEDGERFLOW_ACCESS_TOKEN='<customer-token-from-local-keycloak>'
make demo-observability
```

The demonstration reports the durable business result first, prints its trace and correlation IDs, then verifies the same trace in Tempo and correlated logs in Loki. It returns a distinct failure if telemetry is unavailable; telemetry failure never changes or rolls back the order. See [observability design and SLOs](docs/observability.md) and the [alert runbook](docs/observability-runbook.md).

## Project structure

- `application` — the single deployable Spring Boot application and cross-module verification suites.
- `modules/orders` — order feature boundary.
- `modules/payments` — payment feature boundary.
- `modules/ledger` — ledger feature boundary.
- `modules/messaging` — messaging feature boundary.
- `modules/notifications` — notification feature boundary.
- `modules/operations` — operational health, dependency validation, graceful drain, and secured recovery coordination.

Each feature is a Gradle library under `com.ledgerflow.<feature>`. Application code remains package-by-feature; repository-wide controller, service, repository, entity, and model packages are forbidden.

See [development workflow](docs/development-workflow.md), [architecture](docs/architecture.md),
[deployment security](docs/deployment-security.md), and the
[MVP ExecPlan](docs/plans/mvp-execplan.md) for the governing details. Release inventories are
available for [dependencies and licenses](docs/dependency-inventory.md),
[Flyway migrations](docs/migration-inventory.md), [ADRs](docs/adr/README.md), and
[runbooks](docs/runbook-index.md). Failure demonstrations are described in the
[failure-injection guide](docs/failure-injection.md).
