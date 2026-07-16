# Screenshots Capture Guide

When capturing screenshots for the portfolio or README, focus on evidence of correct execution and robust observability. Ensure sensitive data (like real local passwords or actual customer PII, even if mocked) is obfuscated if necessary.

## 1. Test Suite Pass
- **Action**: Run `./gradlew clean verify`
- **Capture**: The bottom of the terminal output showing `BUILD SUCCESSFUL` and the number of tasks executed, proving a clean green build with all architecture and security tests passing.

## 2. Local Infrastructure Health
- **Action**: Run `./scripts/dev-status`
- **Capture**: The docker compose ps output showing all 9 containers (PostgreSQL, Kafka, Valkey, Keycloak, OpenTelemetry Collector, Prometheus, Grafana, Tempo, Loki) in a `healthy` state.

## 3. Idempotent API Response
- **Action**: Make two identical POST requests to `/api/v1/orders` using curl or Postman.
- **Capture**: The second response showing a `201 Created` status with the exact same payload, and the specific header `Idempotency-Replayed: true`.

## 4. Distributed Tracing in Grafana
- **Action**: Open Grafana at `http://localhost:3000` and navigate to the LedgerFlow Trace dashboard. Find the trace for an order creation.
- **Capture**: The Tempo waterfall view showing spans from the API controller, down to the database transaction, the external mock payment provider HTTP call, and the async outbox publisher span.

## 5. Correlated Logs
- **Action**: In Grafana, switch to the Logs panel for the same trace ID.
- **Capture**: The structured JSON logs (from Loki) clearly showing the `trace_id` and `span_id` injected automatically, with no sensitive PII exposed.
