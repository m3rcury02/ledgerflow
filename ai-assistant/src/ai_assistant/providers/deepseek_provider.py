"""DeepSeek provider. Optional - requires AI_ASSISTANT_DEEPSEEK_API_KEY.

DeepSeek's API is Chat-Completions-compatible, not Responses-API-compatible - it is reached
through the same `openai` SDK already a dependency for OpenAIProvider, but pointed at
`https://api.deepseek.com` and calling `client.chat.completions.create()` instead of
`client.responses.create()`. Every behavioral claim below was checked against the live API with
a real key on 2026-07-21, not assumed from prior knowledge (this vendor's API and a prior
web-search pass over its docs disagreed with each other, which is exactly why a real call
mattered here):

- `response_format={"type": "json_schema", ...}` is rejected outright
  (`"This response_format type is unavailable now"`). Only `{"type": "json_object"}` works, and
  it enforces no schema at all - a model given the shared `prompt.py` system prompt alone (which
  never enumerates `_ModelOutput`'s fields; OpenAI gets that structure from its Responses API
  `json_schema` parameter, not from prompt text) reliably invents its own unrelated JSON shape.
  So, uniquely to this provider, the exact required field list is appended to the system
  message here (`_JSON_SCHEMA_INSTRUCTION`) - confirmed empirically to produce a parseable
  `_ModelOutput` every time it was tried.
- The model has "thinking" enabled by default: hidden reasoning tokens are drawn from the same
  `max_tokens` budget as visible output (observed ~55-75% of `completion_tokens` as
  `reasoning_tokens` in real calls), which is why this provider does not lower the shared
  `max_output_tokens` default for DeepSeek.
- `usage` carries real `prompt_cache_hit_tokens`/`prompt_cache_miss_tokens` counts (confirmed
  present, at top level, in a real response), which is why pricing here is a 3-tuple rather
  than the OpenAI provider's (input, output) pair - the actual post-call cost can use the far
  cheaper cache-hit rate where it genuinely applied, instead of always assuming a miss.
"""

from __future__ import annotations

import time

import httpx
from openai import OpenAI
from pydantic import BaseModel, ValidationError

from ai_assistant.config import DEEPSEEK_MODEL_PRICING_PER_MILLION_TOKENS, Settings
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

_JSON_SCHEMA_INSTRUCTION = (
    "Respond only with a single JSON object - no markdown code fences, no extra keys - matching "
    'exactly this shape: {"summary": string, "evidence": [string, ...], '
    '"confidence": "low" | "medium" | "high", "uncertainty": string, '
    '"suggested_steps": [string, ...], "cited_alert_names": [string, ...]}. '
    "cited_alert_names must contain only alert names copied verbatim from the RETRIEVED "
    "RUNBOOKS section above, or be empty."
)


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


class DeepSeekProvider(Provider):
    name = "deepseek"

    def __init__(self, settings: Settings, http_client: httpx.Client | None = None) -> None:
        if not settings.deepseek_api_key:
            raise ValueError("AI_ASSISTANT_DEEPSEEK_API_KEY is required for the deepseek provider.")
        self._settings = settings
        self._client = OpenAI(
            api_key=settings.deepseek_api_key,
            base_url="https://api.deepseek.com",
            timeout=settings.timeout_seconds,
            http_client=http_client,
        )

    def _pricing(self) -> tuple[float, float, float]:
        pricing = DEEPSEEK_MODEL_PRICING_PER_MILLION_TOKENS.get(self._settings.deepseek_model)
        if pricing is None:
            raise ValueError(
                f"No known pricing for model {self._settings.deepseek_model!r}; add it to "
                "config.DEEPSEEK_MODEL_PRICING_PER_MILLION_TOKENS before using it, so the cost "
                "ceiling guard below can actually enforce a bound."
            )
        return pricing

    def _summarize_sanitized(
        self, sanitized: SanitizedIncidentRequest, retrieved: list[RunbookEntry]
    ) -> IncidentSummary:
        truncated = self._truncate(sanitized)
        messages = build_messages(truncated, retrieved)
        # messages[0] is always the system message - see prompt.py:build_messages. Appending
        # here (rather than editing the shared system prompt) keeps the schema-in-prompt
        # workaround local to the one provider that actually needs it.
        messages[0]["content"] += "\n\n" + _JSON_SCHEMA_INSTRUCTION
        cache_miss_price, cache_hit_price, output_price = self._pricing()

        estimated_input_tokens = sum(_estimate_tokens(m["content"]) for m in messages)
        worst_case_cost = (
            estimated_input_tokens * cache_miss_price
            + self._settings.max_output_tokens * output_price
        ) / 1_000_000
        if worst_case_cost > self._settings.max_cost_usd_per_request:
            raise CostCeilingExceededError(
                f"Worst-case cost ${worst_case_cost:.4f} exceeds the configured ceiling "
                f"${self._settings.max_cost_usd_per_request:.4f}; lower max_output_tokens or "
                "raise the ceiling."
            )

        start = time.perf_counter()
        response = self._client.chat.completions.create(
            model=self._settings.deepseek_model,
            messages=messages,
            response_format={"type": "json_object"},
            max_tokens=self._settings.max_output_tokens,
        )
        latency_ms = (time.perf_counter() - start) * 1000

        model_output = self._parse(response.choices[0].message.content)
        citations = self._ground_citations(model_output.cited_alert_names, retrieved)
        tokens, cost = self._usage(response, cache_miss_price, cache_hit_price, output_price)

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
    def _parse(content: str | None) -> _ModelOutput:
        if content is None:
            raise ValueError("DeepSeek returned no content (empty completion).")
        try:
            return _ModelOutput.model_validate_json(content)
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
        response: object,
        cache_miss_price: float,
        cache_hit_price: float,
        output_price: float,
    ) -> tuple[TokenUsage | None, float]:
        usage = getattr(response, "usage", None)
        if usage is None:
            return None, 0.0
        prompt_tokens = getattr(usage, "prompt_tokens", 0)
        completion_tokens = getattr(usage, "completion_tokens", 0)
        total_tokens = getattr(usage, "total_tokens", prompt_tokens + completion_tokens)

        cache_hit_tokens = getattr(usage, "prompt_cache_hit_tokens", None)
        cache_miss_tokens = getattr(usage, "prompt_cache_miss_tokens", None)
        if cache_hit_tokens is None or cache_miss_tokens is None:
            # Fall back to the conservative (never under-bills) assumption that every prompt
            # token was a cache miss, matching OpenAIProvider's simpler two-rate model.
            cache_hit_tokens, cache_miss_tokens = 0, prompt_tokens

        cost = (
            cache_hit_tokens * cache_hit_price
            + cache_miss_tokens * cache_miss_price
            + completion_tokens * output_price
        ) / 1_000_000
        return (
            TokenUsage(
                input_tokens=prompt_tokens,
                output_tokens=completion_tokens,
                total_tokens=total_tokens,
            ),
            cost,
        )
