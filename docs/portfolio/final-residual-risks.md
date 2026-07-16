# Final Residual Risks

While LedgerFlow successfully implements exactly-once semantics, idempotency, strict module boundaries, and operator recovery, a few residual risks remain:

1. **Database Bottleneck on Outbox Pattern**:
   - *Risk*: Using the `outbox_events` table and a `SKIP LOCKED` publisher places heavy load on the single PostgreSQL database during traffic spikes.
   - *Mitigation*: For extreme scale, this would be swapped out for a Change Data Capture (CDC) based tool like Debezium, offloading the polling overhead from the database.

2. **Poison Pills in Kafka DLT**:
   - *Risk*: Malformed events sent to the DLT currently trigger alerts and require manual/operator intervention to resolve. If a systemic bug produces thousands of poison pills, the operator APIs might be overwhelmed.
   - *Mitigation*: Rate-limiting on the operator API and bulk-retry logic in future extensions.

3. **External Provider Downtime**:
   - *Risk*: The mock payment provider represents a third-party dependency. If the actual provider goes down, LedgerFlow must rely on its timeouts and circuit breakers, resulting in a degraded user experience (503s).
   - *Mitigation*: Background recovery tasks handle timeouts, but synchronous API calls will fail fast. Multi-provider routing is a future consideration.

4. **Operator API Misuse**:
   - *Risk*: Even with strict RBAC, a compromised operator account with `break-glass` privileges could attempt malicious retries.
   - *Mitigation*: All operator actions are immutable and strictly audited in `message_replay_audit`. Alerting is set up for any break-glass usage.
