# Performance Evidence

LedgerFlow prioritizes strict consistency and accurate financial processing over pure throughput, avoiding inflated production scale claims.

## Local Benchmarks and Configuration

- **Rate Limiting**: Configured at the API gateway layer, limiting creation attempts to 60 per authenticated subject per minute (`LEDGERFLOW_WRITE_RATE_LIMIT_REQUESTS=60`). This protects the database from denial-of-service and runaway scripts.
- **Worker Concurrency**: The outbox publisher and background retry workers rely on `SELECT ... FOR UPDATE SKIP LOCKED` to safely process concurrent rows. Concurrency limits are configurable via application properties to ensure the connection pool is never starved.
- **Provider Timeouts**: Hard connection, read, and active operation timeouts are enforced for outbound API calls to mock payment providers to prevent thread exhaustion during external degradation.

## Scalability Principle
*No inflated production scale claims.*

LedgerFlow is currently designed for moderate-scale environments. At extreme scales, the database-backed outbox may become a bottleneck, which would require migrating to a Change Data Capture (CDC) based outbox (e.g., Debezium) or sharding the PostgreSQL database, though that is out of scope for this MVP portfolio release.
