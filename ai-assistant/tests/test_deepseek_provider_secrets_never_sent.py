"""DeepSeek-provider counterpart to test_openai_provider_secrets_never_sent.py.

Mocks the HTTP transport the `openai` SDK actually uses (`httpx.MockTransport`) and asserts on
the real outbound request body DeepSeekProvider constructs. The mock response JSON shape below
was captured from a real `https://api.deepseek.com/chat/completions` call with a live key on
2026-07-21 (see providers/deepseek_provider.py's module docstring), not assumed - DeepSeek's
Chat-Completions-shaped response (top-level `choices[0].message.content`,
`prompt_cache_hit_tokens`/`prompt_cache_miss_tokens` in `usage`) differs from the OpenAI
Responses API shape the sibling test file mocks.
"""

from __future__ import annotations

import json

import httpx
import pytest

from ai_assistant.config import Settings
from ai_assistant.models import IncidentRequest
from ai_assistant.providers.deepseek_provider import DeepSeekProvider
from ai_assistant.runbooks import retrieve
from ai_assistant.sanitizer import sanitize


def _fake_deepseek_key() -> str:
    return "sk-" + "z" * 20


def _fake_aws_key() -> str:
    return "AKIA" + "X" * 16


def _model_output_json() -> str:
    return json.dumps(
        {
            "summary": "Test summary.",
            "evidence": ["evidence line"],
            "confidence": "medium",
            "uncertainty": "some uncertainty",
            "suggested_steps": ["step one"],
            "cited_alert_names": [],
        }
    )


def _mock_chat_completion_payload(
    *, prompt_cache_hit_tokens: int = 0, prompt_cache_miss_tokens: int = 169
) -> dict:
    return {
        "id": "chatcmpl-test123",
        "object": "chat.completion",
        "created": 0,
        "model": "deepseek-v4-flash",
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": _model_output_json()},
                "finish_reason": "stop",
            }
        ],
        "usage": {
            "prompt_tokens": prompt_cache_hit_tokens + prompt_cache_miss_tokens,
            "completion_tokens": 45,
            "total_tokens": prompt_cache_hit_tokens + prompt_cache_miss_tokens + 45,
            "prompt_tokens_details": {"cached_tokens": prompt_cache_hit_tokens},
            "completion_tokens_details": {"reasoning_tokens": 20},
            "prompt_cache_hit_tokens": prompt_cache_hit_tokens,
            "prompt_cache_miss_tokens": prompt_cache_miss_tokens,
        },
    }


@pytest.fixture
def captured_requests() -> list[httpx.Request]:
    return []


def _make_provider(
    captured: list[httpx.Request],
    *,
    prompt_cache_hit_tokens: int = 0,
    prompt_cache_miss_tokens: int = 169,
) -> DeepSeekProvider:
    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(
            200,
            json=_mock_chat_completion_payload(
                prompt_cache_hit_tokens=prompt_cache_hit_tokens,
                prompt_cache_miss_tokens=prompt_cache_miss_tokens,
            ),
        )

    mock_client = httpx.Client(transport=httpx.MockTransport(handler))
    settings = Settings(
        provider="deepseek",
        deepseek_api_key="sk-test-not-a-real-key",
        max_cost_usd_per_request=1.0,  # generous, so the pre-flight guard never trips here
    )
    return DeepSeekProvider(settings, http_client=mock_client)


def test_raw_secret_never_appears_in_outbound_request(captured_requests):
    secret = _fake_deepseek_key()
    raw = IncidentRequest(
        description="incident",
        telemetry_excerpt=f"leaked credential: {secret}",
    )
    sanitized = sanitize(raw)
    assert secret not in sanitized.telemetry_excerpt  # sanity: sanitizer did its job first

    provider = _make_provider(captured_requests)
    retrieved = retrieve(None, sanitized.description, sanitized.telemetry_excerpt)
    provider.summarize(sanitized, retrieved)

    assert len(captured_requests) == 1
    body = captured_requests[0].content.decode()
    assert secret not in body
    assert "[REDACTED:vendor-api-key]" in body


def test_multiple_secret_shapes_never_appear_in_outbound_request(captured_requests):
    deepseek_key = _fake_deepseek_key()
    aws_key = _fake_aws_key()
    raw = IncidentRequest(
        description=f"found {deepseek_key} and {aws_key} in the log dump",
    )
    sanitized = sanitize(raw)

    provider = _make_provider(captured_requests)
    provider.summarize(sanitized, [])

    body = captured_requests[0].content.decode()
    assert deepseek_key not in body
    assert aws_key not in body


def test_rejects_raw_unsanitized_request(captured_requests):
    from ai_assistant.models import IncidentRequest as RawRequest
    from ai_assistant.providers.base import UnsanitizedRequestError

    provider = _make_provider(captured_requests)
    raw = RawRequest(description="incident")
    with pytest.raises(UnsanitizedRequestError):
        provider.summarize(raw, [])  # type: ignore[arg-type]
    assert captured_requests == [], "no network call should happen for an unsanitized request"


def test_cost_ceiling_guard_prevents_the_network_call(captured_requests):
    from ai_assistant.providers.deepseek_provider import CostCeilingExceededError

    def handler(request: httpx.Request) -> httpx.Response:
        captured_requests.append(request)
        return httpx.Response(200, json=_mock_chat_completion_payload())

    mock_client = httpx.Client(transport=httpx.MockTransport(handler))
    settings = Settings(
        provider="deepseek",
        deepseek_api_key="sk-test-not-a-real-key",
        max_output_tokens=1_000_000,  # deliberately huge to blow the ceiling
        max_cost_usd_per_request=0.0001,
    )
    provider = DeepSeekProvider(settings, http_client=mock_client)
    sanitized = sanitize(IncidentRequest(description="incident"))

    with pytest.raises(CostCeilingExceededError):
        provider.summarize(sanitized, [])
    assert captured_requests == [], "cost ceiling must be enforced before any network call"


def test_real_usage_prefers_the_cheaper_cache_hit_rate(captured_requests):
    """DeepSeek's real `usage` splits prompt tokens into cache-hit/cache-miss counts (see the
    module docstring); the reported cost must actually use the cheaper cache-hit rate for the
    portion that hit, not just the conservative all-miss estimate used for the pre-flight
    ceiling guard."""
    provider = _make_provider(
        captured_requests, prompt_cache_hit_tokens=100, prompt_cache_miss_tokens=69
    )
    sanitized = sanitize(IncidentRequest(description="incident"))

    summary = provider.summarize(sanitized, [])

    cache_miss_price, cache_hit_price, output_price = 0.14, 0.0028, 0.28
    expected_cost = (100 * cache_hit_price + 69 * cache_miss_price + 45 * output_price) / 1_000_000
    all_miss_cost = (169 * cache_miss_price + 45 * output_price) / 1_000_000
    assert summary.estimated_cost_usd == pytest.approx(expected_cost)
    assert summary.estimated_cost_usd < all_miss_cost
