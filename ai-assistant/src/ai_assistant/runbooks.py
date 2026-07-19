"""Curated runbook corpus and retrieval.

The 16 entries below are transcribed verbatim from `docs/observability-runbook.md` (not
paraphrased or invented) so every citation this assistant produces is grounded in this
repository's own, already-reviewed operational documentation. Retrieval is deliberately
simple keyword/alert-name overlap over this fixed, small (16-entry) corpus, not embeddings or
a vector store - that machinery would be pure ceremony at this scale. Retrieval happens in the
service layer *before* any provider is called (see service.py), so a citation reflects what
was actually found in the corpus, never something a model claims on its own.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

_SOURCE_DOC = "docs/observability-runbook.md"

_STOPWORDS = {
    "the",
    "a",
    "an",
    "is",
    "are",
    "was",
    "were",
    "and",
    "or",
    "of",
    "to",
    "in",
    "on",
    "for",
    "with",
    "at",
    "by",
    "it",
    "its",
    "be",
    "as",
    "not",
    "no",
    "may",
    "must",
    "this",
    "that",
    "than",
}


@dataclass(frozen=True)
class RunbookEntry:
    alert_name: str
    diagnosis: str
    impact: str
    safe_immediate_actions: str
    escalation: str
    recovery_verification: str

    @property
    def source(self) -> str:
        anchor = self.alert_name.lower()
        return f"{_SOURCE_DOC}#{anchor}"

    def excerpt(self) -> str:
        return f"{self.diagnosis} {self.impact}"


CORPUS: tuple[RunbookEntry, ...] = (
    RunbookEntry(
        alert_name="LedgerFlowApiFailureRate",
        diagnosis="Confirm the 5xx ratio and request rate on `LedgerFlow — API`; split errors by safe status/outcome, then correlate a sampled trace with Loki.",
        impact="Customers may be unable to create or read orders; an uncertain create must be retried with the same key.",
        safe_immediate_actions="Stop a bad rollout, reduce ingress load, and restore required PostgreSQL/provider connectivity. Do not delete idempotency rows or retry with new provider IDs.",
        escalation="Page the application owner and database/provider owner if the ratio persists for one evaluation window.",
        recovery_verification="Error ratio remains below 5%, readiness is up, and same-key replay returns the durable result.",
    ),
    RunbookEntry(
        alert_name="LedgerFlowApiLatency",
        diagnosis="Use `LedgerFlow — API`, then compare provider duration, database-pool pressure, and executor panels. Separate declines from system slowness.",
        impact="Clients may time out and replay while the original workflow is still safe but active.",
        safe_immediate_actions="Apply ingress backpressure and restore the saturated dependency. Keep timeouts and stable operation identities intact.",
        escalation="Engage the provider or database owner when its panel explains sustained p95 latency.",
        recovery_verification="POST p95 stays under 2 seconds with representative traffic and no growth in unknown outcomes.",
    ),
    RunbookEntry(
        alert_name="LedgerFlowProviderCircuitOpen",
        diagnosis="Inspect circuit state/transitions, provider outcomes, timeouts, and bulkhead availability on `LedgerFlow — Provider and Payment`.",
        impact="New provider work fails fast into a durable retry-pending outcome; confirmed declines are unaffected.",
        safe_immediate_actions="Verify provider reachability/TLS/configuration and allow the bounded half-open probe. Do not force-close the circuit or invent a new provider key.",
        escalation="Contact the provider owner when upstream health or contract behavior is the cause.",
        recovery_verification="The circuit returns closed after successful probes and temporary/unknown outcomes subside.",
    ),
    RunbookEntry(
        alert_name="LedgerFlowProviderUnknownOutcomes",
        diagnosis="Correlate timeout counters and provider call/lookup spans; inspect durable payment attempt history using approved database read access.",
        impact="Payment outcome is uncertain and must be reconciled before any resend.",
        safe_immediate_actions="Restore provider lookup and retry the customer command only with the original HTTP idempotency key. Never change the provider operation ID.",
        escalation="Engage payment/provider owners after five unknowns or any unresolved captured-money risk.",
        recovery_verification="Lookup resolves each operation to success/decline/not-found and no duplicate provider or ledger effect exists.",
    ),
    RunbookEntry(
        alert_name="LedgerFlowOutboxBacklog",
        diagnosis="Use `LedgerFlow — Outbox, Kafka and Notification` to compare due/failed rows, oldest age, publication outcomes, broker health, and consumer lag.",
        impact="Completed financial work remains durable, but downstream notifications are delayed.",
        safe_immediate_actions="Restore Kafka and publisher capacity. Preserve rows and leases; do not mark published manually.",
        escalation="Engage Kafka and application owners if oldest age remains over 60 seconds.",
        recovery_verification="Oldest age and due count fall, acknowledgements rise, and each event still produces one semantic effect.",
    ),
    RunbookEntry(
        alert_name="LedgerFlowKafkaConsumerLag",
        diagnosis="Inspect the messaging dashboard, Kafka assignments, processing/retry/DLT rates, database pool, and notification delay.",
        impact="Notifications and DLT evidence are delayed; financial state is not rolled back.",
        safe_immediate_actions="Restore consumer/database reachability and remove downstream saturation. Do not skip offsets or increase concurrency without capacity evidence.",
        escalation="Engage Kafka/application owners if lag exceeds the threshold for another five minutes.",
        recovery_verification="Lag trends to baseline and notification delay remains within the provisional objective.",
    ),
    RunbookEntry(
        alert_name="LedgerFlowDeadLetterGrowth",
        diagnosis="Compare DLT growth with validation, integrity, semantic-conflict, and transient retry outcomes on the messaging dashboard.",
        impact="Some notifications were not applied normally and require investigation; replay may be unsafe.",
        safe_immediate_actions="Contain a faulty producer and inspect only sanitized catalog evidence. Do not paste or replay raw records.",
        escalation="Page application/security owners for schema or integrity failures and Kafka owners for broker delivery faults.",
        recovery_verification="DLT growth stops and a corrected producer record is processed idempotently.",
    ),
    RunbookEntry(
        alert_name="LedgerFlowNotificationSemanticConflict",
        diagnosis="Inspect the semantic-conflict series and sanitized non-replayable DLT evidence; verify the producer and topic ACL principal.",
        impact="Conflicting content attempted to reuse a business-effect identity; no second notification is created.",
        safe_immediate_actions="Revoke or contain the producer and preserve evidence. Never replay the conflicting record.",
        escalation="Immediately involve security and financial-integrity owners.",
        recovery_verification="No new conflicts occur and database uniqueness still reports one effect row.",
    ),
    RunbookEntry(
        alert_name="LedgerFlowTerminalDltInput",
        diagnosis="Inspect terminal failure-code distribution and actual DLT coordinates through sanitized database evidence; never inspect raw poison bytes in routine tooling.",
        impact="Malformed input was durably skipped so later partition records can proceed.",
        safe_immediate_actions="Correct the producer/recoverer header contract and verify least-privilege Kafka ACLs.",
        escalation="Engage the producer owner after sustained terminal growth or any suspected hostile input.",
        recovery_verification="New terminal records stop and later offsets on the affected partition advance.",
    ),
    RunbookEntry(
        alert_name="LedgerFlowTerminalDltEvidencePersistenceFailure",
        diagnosis="Inspect PostgreSQL readiness/capacity and the terminal evidence persistence counter; the DLT offset should remain uncommitted.",
        impact="One partition can pause because the application refuses silent poison-record loss.",
        safe_immediate_actions="Restore PostgreSQL; do not advance the Kafka offset or disable evidence constraints.",
        escalation="Page database and messaging owners immediately.",
        recovery_verification="The same coordinate commits one evidence row and later partition records progress.",
    ),
    RunbookEntry(
        alert_name="LedgerFlowNotificationDltConsumerLag",
        diagnosis="Use the messaging and runtime dashboards to check evidence-store availability, consumer assignment, and terminal failures.",
        impact="Dead-letter evidence is delayed and the DLT partition may accumulate.",
        safe_immediate_actions="Restore the catalog consumer and PostgreSQL. Do not increase retention pressure by copying raw payloads elsewhere.",
        escalation="Engage Kafka/database owners when lag persists.",
        recovery_verification="DLT lag falls and durable catalog counts match processed coordinates.",
    ),
    RunbookEntry(
        alert_name="LedgerFlowDatabasePoolExhaustion",
        diagnosis="Use `LedgerFlow — JVM and Dependencies` to confirm active/max ratio, pending borrowers, transaction latency, and readiness.",
        impact="HTTP, outbox, and notification transactions can time out; provider calls must still remain outside transactions.",
        safe_immediate_actions="Reduce admission, stop leaked/slow queries through approved database operations, and restore database capacity. Do not simply raise pool size.",
        escalation="Page database and application owners after five minutes or any financial transaction uncertainty.",
        recovery_verification="Pending borrowers return to zero, utilization is below 90%, and focused idempotency checks pass.",
    ),
    RunbookEntry(
        alert_name="LedgerFlowGracefulDrainFailed",
        diagnosis="Inspect active-work and drain-result metrics plus correlation/trace logs for provider, publisher, or consumer work present at shutdown.",
        impact="Work may resume from a durable unknown state, lease expiry, or Kafka redelivery; it must not be assumed abandoned or completed.",
        safe_immediate_actions="Preserve the instance logs, let stable-ID lookup/outbox lease/inbox recovery run, and avoid manual resend.",
        escalation="Engage the owning module team for any unresolved provider or ledger state.",
        recovery_verification="Recovery converges without duplicate effects and a later shutdown records `completed`.",
    ),
    RunbookEntry(
        alert_name="LedgerFlowTelemetryExportFailure",
        diagnosis="Check Collector exporter failure and queue metrics, Collector logs, Tempo/Loki health, and network/TLS configuration.",
        impact="Trace/log evidence may be incomplete, but business processing must continue unchanged.",
        safe_immediate_actions="Restore the telemetry backend or network and protect Collector capacity. Do not make business success depend on exporter recovery.",
        escalation="Engage the observability-platform owner after ten minutes; escalate separately if missing evidence affects incident response.",
        recovery_verification="Export-failure rates remain zero, new trace IDs appear in Tempo/Loki, and order outcomes are unchanged throughout the outage.",
    ),
    RunbookEntry(
        alert_name="LedgerFlowOperatorRetryStuck",
        diagnosis="Inspect `ledgerflow_operator_commands` and oldest-active age, then correlate the retry-worker span with its linked request/origin traces. Check lease expiry, worker readiness, and the relevant payment/outbox/DLT state using sanitized operator detail.",
        impact="An approved recovery is delayed; an expired lease may be taken over, but a stuck dependency can prevent convergence.",
        safe_immediate_actions="Restore the failing dependency and verify a healthy worker instance is accepting work. Preserve command, lease, audit, provider, outbox, and DLT identities; never complete the command or mutate source rows manually.",
        escalation="Page the application owner after five minutes and the dependency owner when provider, PostgreSQL, or Kafka is causal. Escalate immediately for unresolved provider success or financial inconsistency.",
        recovery_verification="The command reaches `COMPLETED` or bounded `FAILED`, oldest-active age returns below 120 seconds, a takeover (if any) is audited, and no duplicate journal/outbox/notification effect exists.",
    ),
    RunbookEntry(
        alert_name="LedgerFlowOperatorRetryRepeatedFailure",
        diagnosis="Group bounded retry outcomes by operation type, inspect sanitized attempt history and cooldown state, and use linked traces without copying raw payloads or reasons.",
        impact="Operators may exhaust the automatic limit; repeated manual activity can amplify dependency load.",
        safe_immediate_actions="Stop submitting new commands, correct the dependency/root cause, and wait for the transactional cooldown. Use separate admin break-glass approval only after documented review and only within the configured cap.",
        escalation="Engage the owning module and security/incident commander before break-glass. Payment unknown outcomes also require the provider owner.",
        recovery_verification="Failure growth stops, the next approved command converges with original identities, audit evidence remains immutable, and attempts beyond the cap are rejected.",
    ),
)

_BY_ALERT_NAME = {entry.alert_name.lower(): entry for entry in CORPUS}


def _tokenize(text: str) -> set[str]:
    words = re.findall(r"[a-zA-Z]+", text.lower())
    return {w for w in words if w not in _STOPWORDS and len(w) > 2}


def _split_camel_case(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", " ", name)


def retrieve(
    alert_name: str | None, description: str, telemetry_excerpt: str | None, limit: int = 3
) -> list[RunbookEntry]:
    """Return runbook entries actually retrieved from the corpus, best match first.

    An exact (case-insensitive) alert_name match always wins and is returned alone. Otherwise,
    entries are ranked by keyword overlap between the query text and each entry's alert name
    plus diagnosis/impact text; entries with zero overlap are never returned (an empty result
    means "no confident match in the corpus", which the caller must surface honestly rather
    than fabricate a citation for).
    """
    if alert_name:
        exact = _BY_ALERT_NAME.get(alert_name.strip().lower())
        if exact is not None:
            return [exact]

    query_text = " ".join(filter(None, [alert_name, description, telemetry_excerpt]))
    query_tokens = _tokenize(query_text)
    if not query_tokens:
        return []

    scored: list[tuple[int, RunbookEntry]] = []
    for entry in CORPUS:
        entry_tokens = _tokenize(
            f"{_split_camel_case(entry.alert_name)} {entry.diagnosis} {entry.impact}"
        )
        overlap = len(query_tokens & entry_tokens)
        if overlap > 0:
            scored.append((overlap, entry))

    scored.sort(key=lambda pair: pair[0], reverse=True)
    return [entry for _, entry in scored[:limit]]
