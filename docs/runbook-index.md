# LedgerFlow Runbook Index

| Situation | Primary runbook section | Signals / safe first action |
| --- | --- | --- |
| Unknown provider authorization/capture outcome | `runbook.md#unknown-outcome-and-crash-recovery` | Inspect sanitized payment attempts; lookup the stable operation ID before any same-ID resend. |
| Capture accounting or finalization interrupted | `runbook.md#capture-accounting-recovery` | Compare payment state with immutable journal/outbox identities; never edit ledger rows. |
| Public retry-pending or failed workflow | `runbook.md#public-workflow-response-and-recovery` | Use owner-visible state and operator projection; do not infer success from a client timeout. |
| Outbox delayed, failed, or Kafka unavailable | `runbook.md#outbox-inspection-and-delivery-recovery` | Preserve the existing logical event; diagnose broker then use a bounded operator retry. |
| Notification retry or DLT | `runbook.md#notification-and-dead-letter-recovery` | Inspect sanitized evidence; malformed terminal evidence is non-replayable. |
| Operator recovery stuck, repeated, or lease takeover | `observability-runbook.md#ledgerflowoperatorretrystuck` and `observability-runbook.md#ledgerflowoperatorretryrepeatedfailure` | Stop repeated commands, inspect lease/cooldown/attempt state, escalate before break-glass. |
| Health/startup/graceful shutdown | `runbook.md#health-startup-and-shutdown-diagnosis` | Use isolated liveness/readiness; do not expose detailed health publicly. |
| Management endpoint or DLT abuse finding | `runbook.md#milestone-5d-alert-response` | Confirm network isolation or terminal evidence durability before changing retries. |
| Authorization, body limit, or rate limit | `runbook.md#http-security-and-throttling-diagnosis` | Verify role and scope separately; retain fail-closed object filtering and bounds. |
| Security/dependency/container finding | `runbook.md#vulnerability-and-secret-scan-response` | Do not suppress output; use only the documented exact local exception process. |
| API/provider/outbox/Kafka/DLT/database/telemetry alert | `observability-runbook.md` | Each of the 16 alert sections contains diagnosis, impact, dashboard, safe action, escalation, and recovery verification. |
| Local failure demonstration | `failure-injection.md` | Use only local/test allowlisted faults or Testcontainers/Toxiproxy. |

The main recovery runbook prohibits direct ledger/audit mutation and blind provider resend. The
observability runbook is authoritative for alert response. This index is navigation, not a second
set of operational instructions.
