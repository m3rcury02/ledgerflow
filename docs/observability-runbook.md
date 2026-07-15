# LedgerFlow Observability Alert Runbook

- Status: Provisional Milestone 7A operations guidance
- Last updated: 2026-07-15

These thresholds are demonstration defaults, not production paging policy. Use the provisioned
dashboard named in each entry. Never paste tokens, request bodies, payment references, raw Kafka
payloads, poison bytes, SQL parameters, or customer subjects into tickets or chat. Preserve
PostgreSQL financial/audit rows, provider operation IDs, outbox rows, and Kafka offsets.

## LedgerFlowApiFailureRate

- **Diagnosis:** Confirm the 5xx ratio and request rate on `LedgerFlow — API`; split errors by safe status/outcome, then correlate a sampled trace with Loki.
- **Impact:** Customers may be unable to create or read orders; an uncertain create must be retried with the same key.
- **Safe immediate actions:** Stop a bad rollout, reduce ingress load, and restore required PostgreSQL/provider connectivity. Do not delete idempotency rows or retry with new provider IDs.
- **Escalation:** Page the application owner and database/provider owner if the ratio persists for one evaluation window.
- **Recovery verification:** Error ratio remains below 5%, readiness is up, and same-key replay returns the durable result.

## LedgerFlowApiLatency

- **Diagnosis:** Use `LedgerFlow — API`, then compare provider duration, database-pool pressure, and executor panels. Separate declines from system slowness.
- **Impact:** Clients may time out and replay while the original workflow is still safe but active.
- **Safe immediate actions:** Apply ingress backpressure and restore the saturated dependency. Keep timeouts and stable operation identities intact.
- **Escalation:** Engage the provider or database owner when its panel explains sustained p95 latency.
- **Recovery verification:** POST p95 stays under 2 seconds with representative traffic and no growth in unknown outcomes.

## LedgerFlowProviderCircuitOpen

- **Diagnosis:** Inspect circuit state/transitions, provider outcomes, timeouts, and bulkhead availability on `LedgerFlow — Provider and Payment`.
- **Impact:** New provider work fails fast into a durable retry-pending outcome; confirmed declines are unaffected.
- **Safe immediate actions:** Verify provider reachability/TLS/configuration and allow the bounded half-open probe. Do not force-close the circuit or invent a new provider key.
- **Escalation:** Contact the provider owner when upstream health or contract behavior is the cause.
- **Recovery verification:** The circuit returns closed after successful probes and temporary/unknown outcomes subside.

## LedgerFlowProviderUnknownOutcomes

- **Diagnosis:** Correlate timeout counters and provider call/lookup spans; inspect durable payment attempt history using approved database read access.
- **Impact:** Payment outcome is uncertain and must be reconciled before any resend.
- **Safe immediate actions:** Restore provider lookup and retry the customer command only with the original HTTP idempotency key. Never change the provider operation ID.
- **Escalation:** Engage payment/provider owners after five unknowns or any unresolved captured-money risk.
- **Recovery verification:** Lookup resolves each operation to success/decline/not-found and no duplicate provider or ledger effect exists.

## LedgerFlowOutboxBacklog

- **Diagnosis:** Use `LedgerFlow — Outbox, Kafka and Notification` to compare due/failed rows, oldest age, publication outcomes, broker health, and consumer lag.
- **Impact:** Completed financial work remains durable, but downstream notifications are delayed.
- **Safe immediate actions:** Restore Kafka and publisher capacity. Preserve rows and leases; do not mark published manually.
- **Escalation:** Engage Kafka and application owners if oldest age remains over 60 seconds.
- **Recovery verification:** Oldest age and due count fall, acknowledgements rise, and each event still produces one semantic effect.

## LedgerFlowKafkaConsumerLag

- **Diagnosis:** Inspect the messaging dashboard, Kafka assignments, processing/retry/DLT rates, database pool, and notification delay.
- **Impact:** Notifications and DLT evidence are delayed; financial state is not rolled back.
- **Safe immediate actions:** Restore consumer/database reachability and remove downstream saturation. Do not skip offsets or increase concurrency without capacity evidence.
- **Escalation:** Engage Kafka/application owners if lag exceeds the threshold for another five minutes.
- **Recovery verification:** Lag trends to baseline and notification delay remains within the provisional objective.

## LedgerFlowDeadLetterGrowth

- **Diagnosis:** Compare DLT growth with validation, integrity, semantic-conflict, and transient retry outcomes on the messaging dashboard.
- **Impact:** Some notifications were not applied normally and require investigation; replay may be unsafe.
- **Safe immediate actions:** Contain a faulty producer and inspect only sanitized catalog evidence. Do not paste or replay raw records.
- **Escalation:** Page application/security owners for schema or integrity failures and Kafka owners for broker delivery faults.
- **Recovery verification:** DLT growth stops and a corrected producer record is processed idempotently.

## LedgerFlowNotificationSemanticConflict

- **Diagnosis:** Inspect the semantic-conflict series and sanitized non-replayable DLT evidence; verify the producer and topic ACL principal.
- **Impact:** Conflicting content attempted to reuse a business-effect identity; no second notification is created.
- **Safe immediate actions:** Revoke or contain the producer and preserve evidence. Never replay the conflicting record.
- **Escalation:** Immediately involve security and financial-integrity owners.
- **Recovery verification:** No new conflicts occur and database uniqueness still reports one effect row.

## LedgerFlowTerminalDltInput

- **Diagnosis:** Inspect terminal failure-code distribution and actual DLT coordinates through sanitized database evidence; never inspect raw poison bytes in routine tooling.
- **Impact:** Malformed input was durably skipped so later partition records can proceed.
- **Safe immediate actions:** Correct the producer/recoverer header contract and verify least-privilege Kafka ACLs.
- **Escalation:** Engage the producer owner after sustained terminal growth or any suspected hostile input.
- **Recovery verification:** New terminal records stop and later offsets on the affected partition advance.

## LedgerFlowTerminalDltEvidencePersistenceFailure

- **Diagnosis:** Inspect PostgreSQL readiness/capacity and the terminal evidence persistence counter; the DLT offset should remain uncommitted.
- **Impact:** One partition can pause because the application refuses silent poison-record loss.
- **Safe immediate actions:** Restore PostgreSQL; do not advance the Kafka offset or disable evidence constraints.
- **Escalation:** Page database and messaging owners immediately.
- **Recovery verification:** The same coordinate commits one evidence row and later partition records progress.

## LedgerFlowNotificationDltConsumerLag

- **Diagnosis:** Use the messaging and runtime dashboards to check evidence-store availability, consumer assignment, and terminal failures.
- **Impact:** Dead-letter evidence is delayed and the DLT partition may accumulate.
- **Safe immediate actions:** Restore the catalog consumer and PostgreSQL. Do not increase retention pressure by copying raw payloads elsewhere.
- **Escalation:** Engage Kafka/database owners when lag persists.
- **Recovery verification:** DLT lag falls and durable catalog counts match processed coordinates.

## LedgerFlowDatabasePoolExhaustion

- **Diagnosis:** Use `LedgerFlow — JVM and Dependencies` to confirm active/max ratio, pending borrowers, transaction latency, and readiness.
- **Impact:** HTTP, outbox, and notification transactions can time out; provider calls must still remain outside transactions.
- **Safe immediate actions:** Reduce admission, stop leaked/slow queries through approved database operations, and restore database capacity. Do not simply raise pool size.
- **Escalation:** Page database and application owners after five minutes or any financial transaction uncertainty.
- **Recovery verification:** Pending borrowers return to zero, utilization is below 90%, and focused idempotency checks pass.

## LedgerFlowGracefulDrainFailed

- **Diagnosis:** Inspect active-work and drain-result metrics plus correlation/trace logs for provider, publisher, or consumer work present at shutdown.
- **Impact:** Work may resume from a durable unknown state, lease expiry, or Kafka redelivery; it must not be assumed abandoned or completed.
- **Safe immediate actions:** Preserve the instance logs, let stable-ID lookup/outbox lease/inbox recovery run, and avoid manual resend.
- **Escalation:** Engage the owning module team for any unresolved provider or ledger state.
- **Recovery verification:** Recovery converges without duplicate effects and a later shutdown records `completed`.

## LedgerFlowTelemetryExportFailure

- **Diagnosis:** Check Collector exporter failure and queue metrics, Collector logs, Tempo/Loki health, and network/TLS configuration.
- **Impact:** Trace/log evidence may be incomplete, but business processing must continue unchanged.
- **Safe immediate actions:** Restore the telemetry backend or network and protect Collector capacity. Do not make business success depend on exporter recovery.
- **Escalation:** Engage the observability-platform owner after ten minutes; escalate separately if missing evidence affects incident response.
- **Recovery verification:** Export-failure rates remain zero, new trace IDs appear in Tempo/Loki, and order outcomes are unchanged throughout the outage.
