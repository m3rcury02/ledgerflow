"""Evaluation harness: runs every fixture in fixtures/eval_cases.json against the fake
provider (deterministic, no cost, no network - see docs/ai-operations-assistant.md for why
these fixtures are not run against the real OpenAI provider as an automated gate).
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from ai_assistant.models import IncidentRequest
from ai_assistant.providers.fake import FakeProvider
from ai_assistant.runbooks import CORPUS, retrieve
from ai_assistant.sanitizer import sanitize

_FIXTURES_PATH = Path(__file__).parent / "fixtures" / "eval_cases.json"
_CONFIDENCE_RANK = {"low": 0, "medium": 1, "high": 2}
_CORPUS_ALERT_NAMES = {e.alert_name for e in CORPUS}


def _load_fixtures() -> list[dict]:
    return json.loads(_FIXTURES_PATH.read_text())


FIXTURES = _load_fixtures()


def test_fixture_file_has_at_least_twenty_cases():
    assert len(FIXTURES) >= 20


def test_fixture_ids_are_unique():
    ids = [f["id"] for f in FIXTURES]
    assert len(ids) == len(set(ids))


@pytest.mark.parametrize("fixture", FIXTURES, ids=[f["id"] for f in FIXTURES])
def test_eval_fixture(fixture: dict):
    raw = IncidentRequest(
        alert_name=fixture.get("alert_name"),
        description=fixture["description"],
        telemetry_excerpt=fixture.get("telemetry_excerpt"),
    )
    sanitized = sanitize(raw)
    retrieved = retrieve(sanitized.alert_name, sanitized.description, sanitized.telemetry_excerpt)
    summary = FakeProvider().summarize(sanitized, retrieved)

    expect = fixture["expect"]
    cited_names = {c.alert_name for c in summary.cited_runbooks}

    if "min_confidence" in expect:
        assert _CONFIDENCE_RANK[summary.confidence] >= _CONFIDENCE_RANK[expect["min_confidence"]], (
            f"{fixture['id']}: expected confidence >= {expect['min_confidence']}, "
            f"got {summary.confidence}"
        )

    if "cited_alert_names_include" in expect:
        for name in expect["cited_alert_names_include"]:
            assert name in cited_names, f"{fixture['id']}: expected citation of {name}"

    if expect.get("cited_alert_names_empty"):
        assert cited_names == set(), f"{fixture['id']}: expected no citations, got {cited_names}"

    if "cited_alert_names_include_any" in expect:
        candidates = set(expect["cited_alert_names_include_any"])
        assert cited_names & candidates, (
            f"{fixture['id']}: expected at least one of {candidates}, got {cited_names}"
        )

    if expect.get("uncertainty_nonempty"):
        assert summary.uncertainty.strip() != ""

    if "must_not_contain_in_output" in expect:
        # Deliberately excludes `evidence`: it verbatim-quotes sanitized input (labeled
        # "Telemetry excerpt: ..."), so an injection payload's own text legitimately appears
        # there without the assistant having adopted it. The canary must only catch the
        # assistant's own generated claims being steered, not honest quoting of what it saw.
        # Note this exclusion is FakeProvider-specific: for OpenAIProvider, `evidence` is
        # model-authored, so a live-provider run of these fixtures must NOT exclude it.
        combined = " ".join(
            [summary.summary, summary.uncertainty, *summary.suggested_steps]
        ).lower()
        for canary in expect["must_not_contain_in_output"]:
            assert canary.lower() not in combined, (
                f"{fixture['id']}: forbidden phrase {canary!r} found in output"
            )

    if expect.get("cited_alert_names_must_be_subset_of_corpus"):
        assert cited_names <= _CORPUS_ALERT_NAMES, (
            f"{fixture['id']}: cited a name not in the curated corpus: "
            f"{cited_names - _CORPUS_ALERT_NAMES}"
        )
