# Résumé Bullet Suggestions

*Note: These bullets are strictly based on the implemented evidence in LedgerFlow. There are no inflated scale claims.*

- **Backend Development**: Architected a production-ready modular monolith using Java 25, Spring Boot 4.1, and Spring Modulith, enforcing strict boundary checks and clean architecture via automated ArchUnit tests.
- **API Design**: Designed and implemented a contract-first REST API using OpenAPI specifications, integrating strict request validation and explicit idempotency controls to ensure exactly-once business processing.
- **Distributed Systems**: Built a reliable transactional outbox pattern to guarantee event publishing to Apache Kafka (KRaft), integrating consumer retry policies and dead-letter topics (DLT) for failure isolation.
- **Observability**: Configured a complete OpenTelemetry stack, exporting distributed traces and structured logs to Grafana, Tempo, Prometheus, and Loki, enabling exact correlation between API requests and background workers.
- **Security & Authorization**: Integrated Keycloak OIDC to enforce strict scope- and role-based access control (RBAC), alongside a secured Operator API designed for manual break-glass recovery of failed operations.
- **Database & Data Modeling**: Modeled double-entry accounting principles in PostgreSQL, managing atomic transactions, pessimistic locking (`SKIP LOCKED`), and schema migrations via Flyway.
- **Quality Assurance**: Maintained a 100% pass rate across unit, integration, and architecture tests utilizing Testcontainers, while enforcing dependency and container security scanning via Trivy.
