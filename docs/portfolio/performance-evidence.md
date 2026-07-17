# Performance and Resilience Evidence

LedgerFlow prioritizes strict consistency and accurate financial processing over pure throughput, avoiding inflated production scale claims.

## Local Benchmarks and Configuration

- **Rate Limiting**: Configured at the API gateway layer, limiting creation attempts to 60 per authenticated subject per minute (`LEDGERFLOW_WRITE_RATE_LIMIT_REQUESTS=60`). This protects the database from denial-of-service and runaway scripts.
- **Worker Concurrency**: The outbox publisher and background retry workers rely on `SELECT ... FOR UPDATE SKIP LOCKED` to safely process concurrent rows. Concurrency limits are configurable via application properties to ensure the connection pool is never starved.
- **Provider Timeouts**: Hard connection, read, and active operation timeouts are enforced for outbound API calls to mock payment providers to prevent thread exhaustion during external degradation.

## Scalability Principle
*No inflated production scale claims.*

LedgerFlow is currently designed for moderate-scale environments. At extreme scales, the database-backed outbox may become a bottleneck, which would require migrating to a Change Data Capture (CDC) based outbox (e.g., Debezium) or sharding the PostgreSQL database, though that is out of scope for this MVP portfolio release.

## Automated Performance and Resilience Experiments

An automated test suite exists at `performance/scenarios/` and can be run locally using `./scripts/run-k6-experiments.sh`. 

### Normal Traffic Baseline
* **Hypothesis**: The system can comfortably handle normal traffic without violating rate limits or timing out.
* **Environment requirements**: Local docker-compose stack.
* **Test data**: Pseudo-random UUIDs for idempotency keys, fixed currency amounts.
* **Virtual Users and Duration**: 5 VUs for 15s.
* **Thresholds**: p(95) response time < 200ms, 0% failure rate.
* **Expected Invariant**: All requests are acknowledged with `201 Created` under normal load.
* **Measurements**: Response time percentiles, request success rate, throughput (req/s).
* **Cleanup**: No specific cleanup needed, data persists.
* **Result artifact format**: Summary JSON export via `k6 --summary-export`.

### Burst Traffic (within Baseline)
* **Hypothesis**: The application will accept a ramping burst up to 20 VUs without catastrophic failure, scaling CPU gracefully.
* **Environment requirements**: Local docker-compose stack.
* **Test data**: Pseudo-random UUIDs for idempotency keys.
* **Virtual Users and Duration**: Ramping from 0 to 20 VUs over 15s.
* **Thresholds**: p(95) response time < 500ms, 0% failure rate.
* **Expected Invariant**: The database connection pool queues requests instead of rejecting them.
* **Measurements**: Request duration and throughput under peak load.
* **Cleanup**: No specific cleanup needed.
* **Result artifact format**: Summary JSON export via `k6 --summary-export`.

### Contested Idempotency
* **Hypothesis**: Sending exactly the same payload concurrently with the same `Idempotency-Key` correctly processes the payment once and serves the cached response for the others.
* **Environment requirements**: Local docker-compose stack.
* **Test data**: 10 distinct idempotency keys, re-sent by 10 VUs for 5 iterations each.
* **Virtual Users and Duration**: 10 VUs for max 40s (5 iterations per VU).
* **Thresholds**: HTTP 2xx rate 100%, 0% failure rate.
* **Expected Invariant**: Only one database commit per unique idempotency key; exact state matching for concurrent replies.
* **Measurements**: Response code distribution, race-condition error rate.
* **Cleanup**: No specific cleanup needed.
* **Result artifact format**: Summary JSON export via `k6 --summary-export`.

### Other Destructive Scenarios (Manual Execution)
The following scripts have been drafted but are marked for manual execution, as they involve infrastructure manipulation (e.g., stopping Docker containers) and may corrupt the local environment or leave dangling locks:
- **Kafka outage and recovery**
- **Duplicate Kafka event delivery**
- **Database lock contention**
- **Worker restart**
- **Provider timeouts & slow provider**

For each of these manual tests, the environment requirements include direct access to Docker or the Postgres instance. Test data involves targeted disruption. Clean up requires a full `docker-compose down -v` to ensure database cleanliness.
