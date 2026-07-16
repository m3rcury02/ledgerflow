# Interview Discussion Guide

This guide highlights specific areas of the LedgerFlow codebase that demonstrate engineering maturity during a technical interview.

## 1. Modular Monolith vs. Microservices
**Discussion Point**: Why did you choose a modular monolith instead of microservices?
**Evidence**: Point to `Spring Modulith` and ArchUnit configurations in the `application` module.
**Talking Points**:
- Avoids distributed transaction complexity (e.g., sagas) where atomic local transactions suffice.
- Eases local development and testing while still maintaining strict boundaries.
- Allows later extraction of modules (e.g., splitting out `payments` or `ledger`) if independent scaling is proven necessary.

## 2. Exactly-Once Processing (Idempotency)
**Discussion Point**: How do you prevent users from being charged twice?
**Evidence**: Point to the `Idempotency-Key` implementation and database unique constraints in the `orders` module.
**Talking Points**:
- At-least-once delivery is the baseline; exactly-once is achieved at the application state layer.
- Idempotency keys are bound to a specific payload hash to detect conflicting retries.
- Use of PostgreSQL `INSERT ... ON CONFLICT DO NOTHING` or similar mechanisms guarantees atomic safety.

## 3. The Transactional Outbox Pattern
**Discussion Point**: How do you ensure messages are sent to Kafka after database commits?
**Evidence**: Show the Outbox publisher worker and the `outbox_events` table in `ledgerflow.yaml` and `messaging` module.
**Talking Points**:
- Dual-write problems are avoided. The database transaction inserts both the business entity and the outbox event.
- A background worker reads unpublished events (`SELECT ... FOR UPDATE SKIP LOCKED`) and publishes them to Kafka.
- Only upon a successful Kafka ACK is the database row marked as published.

## 4. Break-Glass Operator Recovery
**Discussion Point**: How do you securely handle manual operational interventions without exposing production to manual database edits?
**Evidence**: The `/api/v1/operator/*` routes and the `operations` module.
**Talking Points**:
- Operations are exposed via API endpoints secured by strict scopes (`ledgerflow.operations.break-glass`) and Keycloak RBAC.
- Commands create immutable database records.
- Workers execute the retry with `SKIP LOCKED` and temporary lease claims to ensure no concurrent execution.

## 5. Security & Secret Management
**Discussion Point**: How did you handle secrets in this project?
**Evidence**: `.env.example`, `scripts/security-scan`, and Trivy integration.
**Talking Points**:
- No secrets are committed; only `.env.example` placeholders exist.
- Automated pipeline gates check for leaked secrets, vulnerable dependencies, and insecure container images before any merge.
