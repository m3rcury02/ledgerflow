"""OpenAI Responses API provider. Optional - requires AI_ASSISTANT_OPENAI_API_KEY.

Structured-output shape (`text.format` with `type: json_schema`, `strict: True`) and the
`response.usage.input_tokens`/`.output_tokens` field names were verified against OpenAI's live
documentation on 2026-07-19, not assumed from prior knowledge - both the Responses API and the
SDK evolve. See docs/ai-operations-assistant.md.

The model only ever sees `sanitized` (see providers/base.py's runtime guard) and is asked to
choose citations only from the `retrieved` runbook list already in the prompt (prompt.py); this
provider additionally *validates* the model's claimed citations against `retrieved` itself
(`_ground_citations`) and drops anything the model claims that wasn't actually retrieved,
rather than trusting the model's self-report. Cost is bounded twice: a pre-flight estimate
before the network call (refuses to call at all if the worst case would exceed the configured
ceiling) and a real measurement from `response.usage` afterward.
"""

from __future__ import annotations

import time

import httpx
from openai import OpenAI
from pydantic import BaseModel, ValidationError

from ai_assistant.config import MODEL_PRICING_PER_MILLION_TOKENS, Settings
from ai_assistant.models import (
    Confidence,
    IncidentSummary,
    RunbookCitation,
    SanitizedIncidentRequest,
    TokenUsage,
)
from ai_assistant.prompt import build_messages
from ai_assistant.providers.base import Provider
from ai_assistant.runbooks import RunbookEntry

_RESPONSE_SCHEMA: dict = {
    "type": "object",
    "properties": {
        "summary": {"type": "string"},
        "evidence": {"type": "array", "items": {"type": "string"}},
        "confidence": {"type": "string", "enum": ["low", "medium", "high"]},
        "uncertainty": {"type": "string"},
        "suggested_steps": {"type": "array", "items": {"type": "string"}},
        "cited_alert_names": {
            "type": "array",
            "items": {"type": "string"},
            "description": "Alert names copied verbatim from RETRIEVED RUNBOOKS only.",
        },
    },
    "required": [
        "summary",
        "evidence",
        "confidence",
        "uncertainty",
        "suggested_steps",
        "cited_alert_names",
    ],
    "additionalProperties": False,
}


class _ModelOutput(BaseModel):
    summary: str
    evidence: list[str]
    confidence: Confidence
    uncertainty: str
    suggested_steps: list[str]
    cited_alert_names: list[str]


class CostCeilingExceededError(RuntimeError):
    pass


def _estimate_tokens(text: str) -> int:
    """Rough, clearly-approximate token count (~4 characters/token for English text) used
    only for the pre-flight cost guard below - not a real tokenizer. The post-call cost figure
    in the response uses the provider's own reported `usage`, which is exact."""
    return max(1, len(text) // 4)


class OpenAIProvider(Provider):
    name = "openai"

    def __init__(self, settings: Settings, http_client: httpx.Client | None = None) -> None:
        if not settings.openai_api_key:
            raise ValueError("AI_ASSISTANT_OPENAI_API_KEY is required for the openai provider.")
        self._settings = settings
        self._client = OpenAI(
            api_key=settings.openai_api_key,
            timeout=settings.timeout_seconds,
            http_client=http_client,
        )

    def _pricing(self) -> tuple[float, float]:
        pricing = MODEL_PRICING_PER_MILLION_TOKENS.get(self._settings.openai_model)
        if pricing is None:
            raise ValueError(
                f"No known pricing for model {self._settings.openai_model!r}; add it to "
                "config.MODEL_PRICING_PER_MILLION_TOKENS before using it, so the cost "
                "ceiling guard below can actually enforce a bound."
            )
        return pricing

    def _summarize_sanitized(
        self, sanitized: SanitizedIncidentRequest, retrieved: list[RunbookEntry]
    ) -> IncidentSummary:
        truncated = self._truncate(sanitized)
        messages = build_messages(truncated, retrieved)
        input_price, output_price = self._pricing()

        estimated_input_tokens = sum(_estimate_tokens(m["content"]) for m in messages)
        worst_case_cost = (
            estimated_input_tokens * input_price + self._settings.max_output_tokens * output_price
        ) / 1_000_000
        if worst_case_cost > self._settings.max_cost_usd_per_request:
            raise CostCeilingExceededError(
                f"Worst-case cost ${worst_case_cost:.4f} exceeds the configured ceiling "
                f"${self._settings.max_cost_usd_per_request:.4f}; lower max_output_tokens or "
                "raise the ceiling."
            )

        start = time.perf_counter()
        response = self._client.responses.create(
            model=self._settings.openai_model,
            input=messages,
            text={
                "format": {
                    "type": "json_schema",
                    "name": "incident_summary",
                    "schema": _RESPONSE_SCHEMA,
                    "strict": True,
                }
            },
            max_output_tokens=self._settings.max_output_tokens,
        )
        latency_ms = (time.perf_counter() - start) * 1000

        model_output = self._parse(response.output_text)
        citations = self._ground_citations(model_output.cited_alert_names, retrieved)
        tokens, cost = self._usage(response, input_price, output_price)

        return IncidentSummary(
            summary=model_output.summary,
            evidence=model_output.evidence,
            confidence=model_output.confidence,
            uncertainty=model_output.uncertainty,
            suggested_steps=model_output.suggested_steps,
            cited_runbooks=citations,
            provider=self.name,
            latency_ms=latency_ms,
            tokens=tokens,
            estimated_cost_usd=cost,
        )

    def _truncate(self, sanitized: SanitizedIncidentRequest) -> SanitizedIncidentRequest:
        limit = self._settings.max_telemetry_chars
        telemetry = sanitized.telemetry_excerpt
        if telemetry is not None and len(telemetry) > limit:
            telemetry = telemetry[:limit] + " [truncated]"
        return sanitized.model_copy(update={"telemetry_excerpt": telemetry})

    @staticmethod
    def _parse(output_text: str) -> _ModelOutput:
        try:
            return _ModelOutput.model_validate_json(output_text)
        except ValidationError as exc:
            raise ValueError(f"Model output did not match the required schema: {exc}") from exc

    @staticmethod
    def _ground_citations(
        cited_alert_names: list[str], retrieved: list[RunbookEntry]
    ) -> list[RunbookCitation]:
        """Only alert names the model was actually shown (in `retrieved`) become citations -
        anything else the model claims is dropped, not trusted."""
        by_name = {e.alert_name.lower(): e for e in retrieved}
        grounded: list[RunbookCitation] = []
        for name in cited_alert_names:
            entry = by_name.get(name.strip().lower())
            if entry is not None:
                grounded.append(
                    RunbookCitation(
                        alert_name=entry.alert_name,
                        source=entry.source,
                        excerpt=entry.excerpt(),
                    )
                )
        return grounded

    @staticmethod
    def _usage(
        response: object, input_price: float, output_price: float
    ) -> tuple[TokenUsage | None, float]:
        usage = getattr(response, "usage", None)
        if usage is None:
            return None, 0.0
        input_tokens = getattr(usage, "input_tokens", 0)
        output_tokens = getattr(usage, "output_tokens", 0)
        total_tokens = getattr(usage, "total_tokens", input_tokens + output_tokens)
        cost = (input_tokens * input_price + output_tokens * output_price) / 1_000_000
        return (
            TokenUsage(
                input_tokens=input_tokens,
                output_tokens=output_tokens,
                total_tokens=total_tokens,
            ),
            cost,
        )
