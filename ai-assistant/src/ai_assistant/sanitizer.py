"""Telemetry input sanitization.

Implements, in code, the exact policy `docs/observability-runbook.md` already states in
prose: "Never paste tokens, request bodies, payment references, raw Kafka payloads, poison
bytes, SQL parameters, or customer subjects into tickets or chat." This module is the single
place `IncidentRequest` (raw, untrusted) becomes `SanitizedIncidentRequest` (the only type any
provider accepts - see models.py).

What this catches: bearer/Authorization tokens, JWT-shaped strings, common vendor API-key
prefixes (sk-, AKIA, ghp_, xox, AIza, ...), `key=value`/`key: value` pairs whose key looks like
a credential, and userinfo embedded in URLs (`https://user:pass@host`).

What this does NOT catch, by design and stated honestly rather than implied away: any secret
that does not match one of the above shapes - an arbitrary internal token format, a secret
split across lines, a secret with no recognizable prefix. Regex-based redaction is a real,
useful mitigation against the common leak shapes, not a proof that no secret can ever reach a
provider. See docs/ai-operations-assistant.md, "What the sanitizer does and doesn't catch".

Deliberately NOT redacted: UUID-shaped correlation/operation IDs. The runbooks this assistant
retrieves from repeatedly instruct operators to look these up - redacting them would make the
assistant's own output less actionable for the exact diagnostic tasks it exists to support.
"""

from __future__ import annotations

import re
from collections.abc import Callable

from ai_assistant.models import IncidentRequest, SanitizedIncidentRequest


def _whole_match_replacement(name: str) -> str:
    return f"[REDACTED:{name}]"


def _credential_value_replacement(name: str, match: re.Match[str]) -> str:
    # Keep the key name (diagnostically useful - "password=..." vs "token=...") but redact
    # only the value.
    return f"{match.group(1)}=[REDACTED:{name}]"


def _url_userinfo_replacement(name: str, _match: re.Match[str]) -> str:
    return f"://[REDACTED:{name}]@"


_PATTERNS: list[tuple[str, re.Pattern[str], Callable[[str, re.Match[str]], str]]] = [
    (
        "bearer-token",
        re.compile(r"\bBearer\s+[A-Za-z0-9\-._~+/]+=*", re.IGNORECASE),
        lambda name, match: _whole_match_replacement(name),
    ),
    (
        "jwt",
        re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b"),
        lambda name, match: _whole_match_replacement(name),
    ),
    (
        "vendor-api-key",
        re.compile(
            r"\b(?:sk|pk|rk)-[A-Za-z0-9]{16,}\b"
            r"|\bAKIA[A-Z0-9]{16}\b"
            r"|\bgh[pousr]_[A-Za-z0-9]{20,}\b"
            r"|\bxox[baprs]-[A-Za-z0-9-]{10,}\b"
            r"|\bAIza[A-Za-z0-9_-]{30,}\b"
        ),
        lambda name, match: _whole_match_replacement(name),
    ),
    (
        # (?!\[REDACTED:) stops this from re-matching a placeholder an earlier pattern in
        # this list already produced (e.g. "Authorization: [REDACTED:bearer-token]") - without
        # it, this pattern would re-redact (and double-count) text a more specific pattern
        # already handled.
        "credential-assignment",
        re.compile(
            r"(?i)\b(password|secret|token|api[_-]?key|authorization|credential)\b"
            r"\s*[:=]\s*['\"]?(?!\[REDACTED:)([^\s'\"]{4,})['\"]?"
        ),
        _credential_value_replacement,
    ),
    (
        "url-userinfo",
        re.compile(r"://[^\s/@:]+:[^\s/@]+@"),
        _url_userinfo_replacement,
    ),
]


def _redact(text: str) -> tuple[str, int]:
    count = 0
    for name, pattern, replacement in _PATTERNS:

        def sub(match: re.Match[str], _name: str = name, _replacement=replacement) -> str:
            nonlocal count
            count += 1
            return _replacement(_name, match)

        text = pattern.sub(sub, text)

    return text, count


def sanitize(raw: IncidentRequest) -> SanitizedIncidentRequest:
    """The only function in this codebase that constructs a SanitizedIncidentRequest."""
    description, description_count = _redact(raw.description)
    telemetry, telemetry_count = (
        _redact(raw.telemetry_excerpt) if raw.telemetry_excerpt is not None else (None, 0)
    )

    return SanitizedIncidentRequest(
        alert_name=raw.alert_name,
        description=description,
        telemetry_excerpt=telemetry,
        redaction_count=description_count + telemetry_count,
    )
