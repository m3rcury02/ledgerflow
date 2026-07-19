"""The single strongest test in this test suite (see docs/ai-operations-assistant.md).

Mocks the HTTP transport the `openai` SDK actually uses (`httpx.MockTransport`) and asserts on
the real outbound request body OpenAIProvider constructs - this exercises the real
request-building code path, not the fake provider (which makes no network call at all and
would make this assertion vacuous). The mock response JSON shape below was verified against
the installed `openai==2.46.0` SDK's own Pydantic response models empirically (not assumed),
since raw REST shapes and SDK-internal field names are exactly the kind of detail that drifts.
"""

from __future__ import annotations

import json

import httpx
import pytest

from ai_assistant.config import Settings
from ai_assistant.models import IncidentRequest
from ai_assistant.providers.openai_provider import OpenAIProvider
from ai_assistant.runbooks import retrieve
from ai_assistant.sanitizer import sanitize


def _fake_openai_key() -> str:
    return "sk-" + "y" * 20


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


def _mock_responses_payload() -> dict:
    return {
        "id": "resp_test123",
        "object": "response",
        "created_at": 0,
        "model": "gpt-5.6-luna",
        "status": "completed",
        "output": [
            {
                "id": "msg_test123",
                "type": "message",
                "role": "assistant",
                "status": "completed",
                "content": [
                    {"type": "output_text", "text": _model_output_json(), "annotations": []}
                ],
            }
        ],
        "usage": {
            "input_tokens": 123,
            "output_tokens": 45,
            "total_tokens": 168,
            "input_tokens_details": {"cached_tokens": 0, "cache_write_tokens": 0},
            "output_tokens_details": {"reasoning_tokens": 0},
        },
        "parallel_tool_calls": False,
        "tool_choice": "auto",
        "tools": [],
    }


@pytest.fixture
def captured_requests() -> list[httpx.Request]:
    return []


def _make_provider(captured: list[httpx.Request]) -> OpenAIProvider:
    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(request)
        return httpx.Response(200, json=_mock_responses_payload())

    mock_client = httpx.Client(transport=httpx.MockTransport(handler))
    settings = Settings(
        provider="openai",
        openai_api_key="sk-test-not-a-real-key",
        max_cost_usd_per_request=1.0,  # generous, so the pre-flight guard never trips here
    )
    return OpenAIProvider(settings, http_client=mock_client)


def test_raw_secret_never_appears_in_outbound_request(captured_requests):
    secret = _fake_openai_key()
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
    openai_key = _fake_openai_key()
    aws_key = _fake_aws_key()
    raw = IncidentRequest(
        description=f"found {openai_key} and {aws_key} in the log dump",
    )
    sanitized = sanitize(raw)

    provider = _make_provider(captured_requests)
    provider.summarize(sanitized, [])

    body = captured_requests[0].content.decode()
    assert openai_key not in body
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
    from ai_assistant.providers.openai_provider import CostCeilingExceededError

    def handler(request: httpx.Request) -> httpx.Response:
        captured_requests.append(request)
        return httpx.Response(200, json=_mock_responses_payload())

    mock_client = httpx.Client(transport=httpx.MockTransport(handler))
    settings = Settings(
        provider="openai",
        openai_api_key="sk-test-not-a-real-key",
        max_output_tokens=1_000_000,  # deliberately huge to blow the ceiling
        max_cost_usd_per_request=0.0001,
    )
    provider = OpenAIProvider(settings, http_client=mock_client)
    sanitized = sanitize(IncidentRequest(description="incident"))

    with pytest.raises(CostCeilingExceededError):
        provider.summarize(sanitized, [])
    assert captured_requests == [], "cost ceiling must be enforced before any network call"
