"""Deterministic fake provider: no network call, no API key, always available.

This is the default provider (see config.py) - `docker run`/local tests/CI never need an
OpenAI key. It never reasons about the input; it only templates a response directly from
what `runbooks.retrieve()` actually found, which is also why it is structurally immune to
prompt injection (there is no model to inject) and why it cannot leak a secret to a third
party (there is no third party - it makes no network call at all).
"""

from __future__ import annotations

import time

from ai_assistant.models import (
    Confidence,
    IncidentSummary,
    RunbookCitation,
    SanitizedIncidentRequest,
)
from ai_assistant.providers.base import Provider
from ai_assistant.runbooks import RunbookEntry


class FakeProvider(Provider):
    name = "fake"

    def _summarize_sanitized(
        self, sanitized: SanitizedIncidentRequest, retrieved: list[RunbookEntry]
    ) -> IncidentSummary:
        start = time.perf_counter()

        confidence = self._confidence(sanitized, retrieved)
        evidence = self._evidence(sanitized)
        summary, suggested_steps, uncertainty = self._synthesize(retrieved)
        citations = [
            RunbookCitation(alert_name=e.alert_name, source=e.source, excerpt=e.excerpt())
            for e in retrieved
        ]

        latency_ms = (time.perf_counter() - start) * 1000
        return IncidentSummary(
            summary=summary,
            evidence=evidence,
            confidence=confidence,
            uncertainty=uncertainty,
            suggested_steps=suggested_steps,
            cited_runbooks=citations,
            provider=self.name,
            latency_ms=latency_ms,
            tokens=None,
            estimated_cost_usd=0.0,
        )

    @staticmethod
    def _confidence(
        sanitized: SanitizedIncidentRequest, retrieved: list[RunbookEntry]
    ) -> Confidence:
        if not retrieved:
            return "low"
        if (
            sanitized.alert_name
            and len(retrieved) == 1
            and retrieved[0].alert_name.lower() == sanitized.alert_name.strip().lower()
        ):
            return "high"
        return "medium"

    @staticmethod
    def _evidence(sanitized: SanitizedIncidentRequest) -> list[str]:
        evidence = [f"Reported description: {sanitized.description}"]
        if sanitized.telemetry_excerpt:
            evidence.append(f"Telemetry excerpt: {sanitized.telemetry_excerpt}")
        if sanitized.redaction_count:
            evidence.append(
                f"{sanitized.redaction_count} value(s) were redacted from the input before "
                "processing."
            )
        return evidence

    @staticmethod
    def _synthesize(retrieved: list[RunbookEntry]) -> tuple[str, list[str], str]:
        if not retrieved:
            return (
                "No runbook in the curated corpus confidently matched this incident.",
                [
                    "Escalate to the on-call application owner for manual triage.",
                    "Check docs/runbook-index.md for a situation not covered by an alert name.",
                ],
                "The fake provider found no keyword overlap with the curated runbook "
                "corpus; this may mean the incident is outside documented scenarios, or "
                "that the description used different terminology than the runbooks.",
            )

        top = retrieved[0]
        summary = f"Likely matches {top.alert_name}: {top.impact}"
        steps = [top.safe_immediate_actions]
        if len(retrieved) > 1:
            steps.append(
                f"Also consider {', '.join(e.alert_name for e in retrieved[1:])} if the "
                "primary match does not fit."
            )
        steps.append(f"Escalation guidance: {top.escalation}")
        uncertainty = (
            "Single confident match."
            if len(retrieved) == 1
            else f"{len(retrieved)} plausible matches retrieved; confirm against real dashboards "
            "before acting on any one of them."
        )
        return summary, steps, uncertainty
