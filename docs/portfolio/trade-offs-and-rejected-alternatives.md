# Documented Trade-offs and Rejected Alternatives

## 1. Modular Monolith vs. Microservices
- **Decision**: Used a modular monolith (Spring Modulith) over microservices.
- **Trade-off**: While microservices offer independent deployability and scaling, they incur significant overhead in distributed transaction management, network latency, and operational complexity. For a ledger application where atomic cross-module consistency (e.g., Order + Ledger Journal + Outbox) is critical, local database transactions are far simpler and more reliable. We rejected microservices until specific independent scaling needs arise.

## 2. Synchronous REST vs. Asynchronous (Event-Driven) API for Orders
- **Decision**: Order creation is synchronously acknowledged after payment capture and database commit, but downstream processing is asynchronous.
- **Trade-off**: A fully asynchronous API (`202 Accepted`) would yield faster response times and higher write availability, but it forces the client to poll for the final payment status. We chose synchronous capture for the MVP to simplify client integration, shifting only the non-critical downstream notifications to Kafka.

## 3. Dedicated Database per Module vs. Single Database with Schema Isolation
- **Decision**: A single PostgreSQL database is used, but modules do not cross-join tables outside of their boundaries.
- **Trade-off**: True microservices would dictate separate databases to prevent coupling. We compromised by using one database (lowering infrastructure overhead) but enforcing logical separation via ArchUnit rules and code reviews. This allows atomicity while keeping the design clean.

## 4. Kafka KRaft vs. ZooKeeper
- **Decision**: Used Kafka in KRaft mode (no ZooKeeper).
- **Trade-off**: ZooKeeper is a battle-tested consensus mechanism, but KRaft simplifies the infrastructure footprint (fewer containers to manage locally and in production) and is the future standard for Apache Kafka.

## 5. UUIDs vs. Sequential IDs
- **Decision**: Using string/UUID formats for idempotency keys and correlation IDs, but sequential or Snowflake-like IDs could be used for primary keys if scale dictates.
- **Trade-off**: UUIDs are large and can fragment indexes, but they allow offline generation and prevent predictable ID enumeration. For idempotency and external references, UUIDs were selected for their global uniqueness.

## 6. Real Payment Gateway vs. Mock Provider
- **Decision**: Integrated against a local Mock Payment Provider rather than a real Stripe/Adyen sandbox.
- **Trade-off**: A real sandbox offers true end-to-end realism. However, a local mock provider ensures tests are fast, deterministic, offline-capable, and completely immune to external rate limits or network flakes.
