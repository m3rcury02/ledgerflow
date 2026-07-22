# LedgerFlow Dependency and License Inventory

- Resolved evidence date: 2026-07-22
- Resolution source: Gradle `runtimeClasspath`, Spring Boot BOM, explicit platform BOMs,
  `ai-assistant/pyproject.toml`, and `docker compose config --images`
- Legal note: this engineering inventory is not legal advice; a production distributor should
  generate and review a complete SBOM and license-notice bundle.

## Direct runtime families

| Capability | Resolved version | License | Why it is present / operational note |
| --- | --- | --- | --- |
| Java | 25 toolchain | GPLv2 with Classpath Exception for OpenJDK distributions | Language/runtime baseline; deployment selects and patches a supported JRE image. |
| Spring Boot | 4.1.0 | Apache-2.0 | Dependency management, configuration, executable application, health, HTTP, JDBC, security, Kafka, and telemetry integration. |
| Spring Framework | 7.0.8 | Apache-2.0 | Boot-managed web, transaction, JDBC, and application infrastructure. |
| Spring Security | 7.1.0 | Apache-2.0 | OAuth2/OIDC JWT resource server and authorization filters. |
| Spring Data JDBC | 4.1.0 | Apache-2.0 | Boot-managed JDBC infrastructure; feature stores still use explicit `JdbcClient` SQL. |
| Spring Kafka | 4.1.0 | Apache-2.0 | Kafka producer/consumer integration, acknowledgement, retry, and DLT handling. |
| Apache Kafka client | 4.2.1 | Apache-2.0 | Boot-managed wire client; verified against the local Kafka 4.3.1 broker. |
| Flyway Community | 12.4.0 | Apache-2.0 | Forward-only PostgreSQL schema migration. |
| PostgreSQL JDBC | 42.7.12 | BSD-2-Clause | PostgreSQL wire driver; narrowly overrides Boot 4.1.0's 42.7.11 constraint to remediate CVE-2026-54291. Remove the override when the Boot BOM manages this version or newer. |
| HikariCP | 7.0.2 | Apache-2.0 | Boot-managed bounded database connection pool. |
| Micrometer | 1.17.0; tracing 1.7.0 | Apache-2.0 | Bounded application/JVM/Prometheus metrics and tracing bridge. |
| OpenTelemetry API/SDK/exporter | 1.62.0 | Apache-2.0 | W3C propagation, spans/logs, and OTLP export. |
| OpenTelemetry Logback appender | 2.28.1-alpha | Apache-2.0 | Structured log-to-OTLP bridge; alpha status is an explicit residual risk. |
| Resilience4j | 2.4.0 | Apache-2.0 | Provider circuit breaker and zero-queue concurrency bulkhead. |
| Jackson 3 | 3.1.4 | Apache-2.0 | Boot-managed strict JSON and event serialization. |
| Nimbus JOSE JWT | 10.9 | Apache-2.0 | Spring Security's signed JWT validation implementation. |
| Logback | 1.5.34 | EPL-2.0 or LGPL-2.1 | Boot-managed structured application logging backend. |
| Swagger UI WebJar | 5.25.3 | Apache-2.0 | Browser API explorer assets; routes are disabled by default and serve the checked-in OpenAPI contract only when explicitly enabled. |

No H2, ORM, production Redis client, payment SDK, workflow engine, or exactly-once transaction
coordinator is present. Most versions are Spring Boot-managed; explicit versions exist only for
Spring Boot/Modulith, Resilience4j, OpenTelemetry instrumentation, the temporary PostgreSQL JDBC
security override described above, and verification tooling.

Inspect the complete transitive graph with:

```bash
./gradlew --no-daemon :application:dependencies --configuration runtimeClasspath --console=plain
```

## Test and build dependencies

| Dependency | Version source | License | Scope |
| --- | --- | --- | --- |
| JUnit Jupiter, AssertJ, Spring Boot Test | Boot 4.1.0 BOM | EPL-2.0 / Apache-2.0 | unit and integration assertions |
| Testcontainers PostgreSQL/Kafka/Toxiproxy | Boot-imported Testcontainers BOM | MIT | real protocol and failure-path integration tests |
| Spring Modulith | 2.1.0 BOM | Apache-2.0 | module model verification |
| ArchUnit | 1.4.2 | Apache-2.0 | package/module dependency checks |
| Spotless | 8.8.0 | Apache-2.0 | formatting gate |
| Checkstyle | 13.8.0 | LGPL-2.1-or-later | static style/quality checks |
| OpenAPI Generator CLI | 7.23.0 | Apache-2.0 | contract validation only |
| Gradle Wrapper | 9.6.1 | Apache-2.0 | reproducible build entry point |
| Trivy container | 0.72.0 plus pinned digest | Apache-2.0 | secret, misconfiguration, dependency, and image scanning |

## Optional AI assistant dependencies

The Python service is separate from the Java application and is not included in the LedgerFlow
OCI image. All direct dependencies are pinned in `ai-assistant/pyproject.toml`; CI installs the
`dev` extra and runs the tests and Ruff gates from a clean Python 3.12 environment.

| Dependency | Version | License | Scope |
| --- | --- | --- | --- |
| FastAPI | 0.139.2 | MIT | HTTP API and request/response validation |
| Uvicorn | 0.51.0 | BSD-3-Clause | Local ASGI server |
| Pydantic | 2.13.4 | MIT | Structured input/output models |
| pydantic-settings | 2.14.2 | MIT | Environment-only service configuration with service-local dotenv isolation |
| OpenAI Python SDK | 2.46.0 | Apache-2.0 | Optional, explicitly configured Responses API adapter; never used by default tests |
| HTTPX | 0.28.1 | BSD-3-Clause | Bounded outbound HTTP and intercepted provider security tests |
| pytest | 9.1.1 | MIT | Python unit, API, security, and evaluation tests |
| Ruff | 0.15.22 | MIT | Python lint and formatting gate |

DeepSeek support uses the existing HTTPX client directly and adds no vendor SDK. No provider API
key is present in the dependency graph, repository, default configuration, or test environment.

## Local infrastructure images

| Service | Explicit image |
| --- | --- |
| PostgreSQL | `postgres:18.4-trixie@sha256:3a82e1f56c8f0f5616a11103ac3d47e632c3938698946a7ad26da0df1334744a` (digest-pinned — see [container risk register](security/local-development-container-risk-register.md), `LF-DEV-2026-001`) |
| Kafka | `apache/kafka-native:4.3.1` |
| Valkey | `valkey/valkey:9.1.0-alpine3.23` |
| Keycloak | `quay.io/keycloak/keycloak:26.7.0` |
| Tempo | `grafana/tempo:2.10.7` |
| Loki | `grafana/loki:3.7.3` |
| OpenTelemetry Collector Contrib | `otel/opentelemetry-collector-contrib:0.157.0` |
| Prometheus | `prom/prometheus:v3.13.1` |
| Grafana | `grafana/grafana:13.1.1-ubuntu` |

All tags are explicit. PostgreSQL is also repository-digest pinned; the remaining Compose images
are tag pinned, while the local security policy resolves and matches exact digests for accepted
findings. A production image policy must pin and attest every deployment artifact independently.

## Vulnerability and license evidence

Run `scripts/security-scan`. It always prints scanner findings, fails normally for committed
secrets and fixed High/Critical packaged Java findings, and permits only exact unexpired local
Compose records. The MVP release run and result are recorded in `docs/mvp-evidence.md`. CI and
`scripts/scan-image` generate a CycloneDX JSON SBOM for the Java OCI image and scan that image with
Trivy. The optional Python service is not containerized and does not yet have a generated SBOM;
the repository also does not generate a distributable license-notice bundle. Those remain release
requirements for any independently distributed AI-service artifact.
