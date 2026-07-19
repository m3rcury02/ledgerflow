import pytest

from ai_assistant.models import IncidentRequest
from ai_assistant.providers.base import UnsanitizedRequestError
from ai_assistant.providers.fake import FakeProvider
from ai_assistant.runbooks import CORPUS, retrieve
from ai_assistant.sanitizer import sanitize


def _summarize(alert_name=None, description="incident", telemetry=None):
    sanitized = sanitize(
        IncidentRequest(alert_name=alert_name, description=description, telemetry_excerpt=telemetry)
    )
    retrieved = retrieve(alert_name, description, telemetry)
    return FakeProvider().summarize(sanitized, retrieved), retrieved


def test_rejects_raw_unsanitized_request():
    raw = IncidentRequest(description="incident")
    with pytest.raises(UnsanitizedRequestError):
        FakeProvider().summarize(raw, [])  # type: ignore[arg-type]


def test_exact_alert_match_is_high_confidence():
    summary, _ = _summarize(alert_name="LedgerFlowApiFailureRate", description="5xx elevated")
    assert summary.confidence == "high"
    assert len(summary.cited_runbooks) == 1
    assert summary.cited_runbooks[0].alert_name == "LedgerFlowApiFailureRate"


def test_no_match_is_low_confidence_and_no_citations():
    summary, _ = _summarize(description="asdf qwer zxcv nonsense")
    assert summary.confidence == "low"
    assert summary.cited_runbooks == []
    assert (
        "no runbook" in summary.summary.lower()
        or "no confident" in summary.uncertainty.lower()
        or "no keyword overlap" in summary.uncertainty.lower()
    )


def test_provider_and_cost_fields_are_fake_defaults():
    summary, _ = _summarize(alert_name="LedgerFlowOutboxBacklog", description="backlog growing")
    assert summary.provider == "fake"
    assert summary.tokens is None
    assert summary.estimated_cost_usd == 0.0
    assert summary.latency_ms >= 0.0


def test_never_claims_remediation_performed():
    """No automatic remediation: the fake provider's vocabulary never claims an action was
    taken, only suggested."""
    for entry in CORPUS:
        summary, _ = _summarize(alert_name=entry.alert_name, description=entry.impact)
        combined = " ".join([summary.summary, *summary.suggested_steps]).lower()
        assert "i have fixed" not in combined
        assert "remediation complete" not in combined
        assert "i restarted" not in combined


def test_citations_are_grounded_in_retrieved_corpus_not_invented():
    summary, retrieved = _summarize(
        alert_name="LedgerFlowDatabasePoolExhaustion", description="pool exhausted"
    )
    retrieved_names = {e.alert_name for e in retrieved}
    cited_names = {c.alert_name for c in summary.cited_runbooks}
    assert cited_names <= retrieved_names


def test_redaction_count_surfaces_in_evidence():
    summary, _ = _summarize(description="password=abcd1234efgh secret in logs")
    assert any("redacted" in e.lower() for e in summary.evidence)


def test_deterministic_same_input_same_output():
    a, retrieved_a = _summarize(alert_name="LedgerFlowKafkaConsumerLag", description="lag rising")
    b, retrieved_b = _summarize(alert_name="LedgerFlowKafkaConsumerLag", description="lag rising")
    assert a.summary == b.summary
    assert a.suggested_steps == b.suggested_steps
    assert a.confidence == b.confidence
