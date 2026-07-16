# Demo Video Script

## 1. Introduction (0:00 - 0:30)
* **Visual**: Title slide with "LedgerFlow" and my name.
* **Audio**: "Hi, I'm [My Name]. Welcome to my demonstration of LedgerFlow, a modular monolith built with Java 25 and Spring Boot. It emphasizes contract-first APIs, idempotency, and robust distributed tracing."

## 2. Local Environment Setup (0:30 - 1:00)
* **Visual**: Terminal running `./scripts/dev-up` and `./scripts/dev-status`. Show all 9 containers healthy.
* **Audio**: "I'll start by bringing up the local infrastructure. LedgerFlow runs alongside PostgreSQL, Kafka, Keycloak for OIDC, Valkey, and an OpenTelemetry observability stack. As you can see, the setup is entirely containerized and verifiable."

## 3. Order Creation & Idempotency (1:00 - 2:00)
* **Visual**: Postman or curl executing a `POST /api/v1/orders` request with a valid JWT and an Idempotency-Key.
* **Audio**: "Here, I'm submitting an order. Notice the Idempotency-Key header. The API responds with a 201 Created. If I send the exact same request again, LedgerFlow intercepts it and safely replays the response without executing duplicate business logic, maintaining strict exactly-once semantics."

## 4. Observability & Tracing (2:00 - 3:00)
* **Visual**: Grafana UI showing the Tempo trace for the order we just created.
* **Audio**: "Let's look at the observability stack. Our OpenTelemetry instrumentation automatically captures the trace. Here in Grafana, we can follow the trace through the API layer, down to the database transactions, out to the mock payment provider, and finally to the Kafka outbox publisher. Logs are perfectly correlated with the trace ID."

## 5. Operator Recovery (3:00 - 4:00)
* **Visual**: Terminal executing an operator break-glass retry command.
* **Audio**: "Failures happen. LedgerFlow includes a secured operator API. With the correct scopes, operators can inspect failed operations and issue break-glass retries. The retry runs through a leased background worker, ensuring safety even in a clustered deployment."

## 6. Conclusion (4:00 - 4:15)
* **Visual**: Quick view of the `./gradlew clean verify` output showing 100% test pass.
* **Audio**: "LedgerFlow uses Spring Modulith and ArchUnit to enforce modularity. Thanks for watching!"
