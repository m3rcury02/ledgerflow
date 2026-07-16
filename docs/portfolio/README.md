# LedgerFlow Portfolio Overview

LedgerFlow is a contract-first, modular-monolithic Java 25 / Spring Boot 4.1 application demonstrating robust architectural patterns, production-ready observability, and secured operational boundaries.

## Key Highlights

- **Contract-First API**: Defined by an OpenAPI specification (`ledgerflow.yaml`) with strict input validation, rate limiting, and zero unknown properties allowed.
- **Idempotency & Safe Retries**: Employs idempotency keys and immutable database operations to ensure that concurrent, duplicate, or replayed requests are handled safely (e.g., exactly-once business semantics over at-least-once delivery).
- **Outbox Pattern**: Atomic transaction updates to PostgreSQL and Kafka (using KRaft) via an outbox pattern for guaranteed message delivery.
- **Operator Recovery API**: Features a robust, secured break-glass recovery mechanism using strict scopes, role-based access control, and leased worker orchestration.
- **Comprehensive Observability**: Pre-configured with OpenTelemetry instrumentation feeding traces to Tempo, metrics to Prometheus, and logs to Loki, easily visualized in a provisioned Grafana dashboard.

## Technical Details

- **Language/Framework**: Java 25, Spring Boot 4.1, Spring Modulith
- **Datastore**: PostgreSQL (managed via Flyway)
- **Messaging**: Kafka (KRaft mode)
- **Caching**: Valkey (ephemeral Redis-compatible store)
- **Security**: Keycloak (OIDC JWT authentication)

For more deep-dive information, refer to the [architecture documentation](../architecture.md) and [threat model](../threat-model.md).
