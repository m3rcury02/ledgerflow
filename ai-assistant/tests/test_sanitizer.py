"""Sanitizer tests.

Fake secrets are built at runtime (string concatenation), never as a single committed
literal - this repository's Trivy-based `scripts/security-scan` scans every file's contents
for secret-shaped patterns, and a literal `sk-...`/`AKIA...`/JWT-shaped string sitting in a
test file would itself trip that scanner. See docs/ai-operations-assistant.md.
"""

from ai_assistant.models import IncidentRequest
from ai_assistant.sanitizer import sanitize


def _fake_jwt() -> str:
    return "eyJ" + "a" * 20 + "." + "b" * 20 + "." + "c" * 20


def _fake_aws_key() -> str:
    return "AKIA" + "X" * 16


def _fake_openai_key() -> str:
    return "sk-" + "y" * 20


def _fake_github_token() -> str:
    return "ghp_" + "z" * 24


def _fake_bearer() -> str:
    return "Bearer " + "q" * 30


def test_redacts_bearer_token():
    raw = IncidentRequest(description=f"curl -H 'Authorization: {_fake_bearer()}' ...")
    result = sanitize(raw)
    assert _fake_bearer() not in result.description
    assert "[REDACTED:bearer-token]" in result.description
    assert result.redaction_count == 1


def test_redacts_jwt():
    raw = IncidentRequest(description=f"token was {_fake_jwt()}")
    result = sanitize(raw)
    assert _fake_jwt() not in result.description
    assert "[REDACTED:jwt]" in result.description


def test_redacts_aws_key():
    raw = IncidentRequest(description=f"found key {_fake_aws_key()} in logs")
    result = sanitize(raw)
    assert _fake_aws_key() not in result.description
    assert "[REDACTED:vendor-api-key]" in result.description


def test_redacts_openai_key():
    raw = IncidentRequest(description=f"OPENAI_API_KEY={_fake_openai_key()}")
    result = sanitize(raw)
    assert _fake_openai_key() not in result.description


def test_redacts_github_token():
    raw = IncidentRequest(description=f"push failed with {_fake_github_token()}")
    result = sanitize(raw)
    assert _fake_github_token() not in result.description


def test_redacts_credential_assignment_keeps_key_name():
    raw = IncidentRequest(description="db connection failed: password=hunter2example")
    result = sanitize(raw)
    assert "hunter2example" not in result.description
    assert "password=[REDACTED:credential-assignment]" in result.description


def test_redacts_url_userinfo():
    raw = IncidentRequest(description="tried https://admin:s3cr3tpass@internal.example/db")
    result = sanitize(raw)
    assert "s3cr3tpass" not in result.description
    assert "admin" not in result.description


def test_preserves_correlation_id():
    """UUID-shaped correlation/operation IDs must survive - runbooks need them for lookup."""
    correlation_id = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    raw = IncidentRequest(description=f"correlation_id={correlation_id} order stuck")
    result = sanitize(raw)
    assert correlation_id in result.description


def test_sanitizes_telemetry_excerpt_separately():
    raw = IncidentRequest(
        description="no secrets here",
        telemetry_excerpt=f"Authorization: {_fake_bearer()}",
    )
    result = sanitize(raw)
    assert _fake_bearer() not in (result.telemetry_excerpt or "")
    assert result.redaction_count == 1


def test_no_redaction_when_clean():
    raw = IncidentRequest(description="API 5xx rate is elevated, readiness is up")
    result = sanitize(raw)
    assert result.description == raw.description
    assert result.redaction_count == 0


def test_multiple_secrets_in_one_field_all_redacted():
    raw = IncidentRequest(description=f"leaked {_fake_openai_key()} and also {_fake_aws_key()}")
    result = sanitize(raw)
    assert _fake_openai_key() not in result.description
    assert _fake_aws_key() not in result.description
    assert result.redaction_count == 2
