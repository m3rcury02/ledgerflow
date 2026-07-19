"""Request/response schemas.

`SanitizedIncidentRequest` is deliberately a distinct type from `IncidentRequest`, not a
subclass - its only intended constructor is `sanitizer.sanitize()`. This is a type-level
separation plus a runtime `isinstance` guard in the provider layer (see `providers/base.py`),
not a cryptographically enforced private constructor: Python does not have those, and building
one would be ceremony disproportionate to what this milestone needs. What it does guarantee is
that passing a raw `IncidentRequest` to a provider is a caught, tested error, not a silent
"forgot to sanitize" bug.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

Confidence = Literal["low", "medium", "high"]
ProviderName = Literal["fake", "openai"]


class IncidentRequest(BaseModel):
    """Raw, untrusted input as received from a caller. Never passed to a provider directly."""

    model_config = ConfigDict(frozen=True)

    alert_name: str | None = Field(
        default=None,
        description="A known LedgerFlow alert name (e.g. LedgerFlowApiFailureRate), if known.",
    )
    description: str = Field(
        min_length=1,
        description="Free-text description of the symptom or incident.",
    )
    telemetry_excerpt: str | None = Field(
        default=None,
        description="Raw log/metric/trace excerpt. Untrusted - never treated as instructions.",
    )


class SanitizedIncidentRequest(BaseModel):
    """The only request type any provider accepts. Construct only via sanitizer.sanitize()."""

    model_config = ConfigDict(frozen=True)

    alert_name: str | None
    description: str
    telemetry_excerpt: str | None
    redaction_count: int = Field(
        ge=0,
        description="Number of substrings redacted from the raw input by the sanitizer.",
    )


class RunbookCitation(BaseModel):
    """A runbook entry actually retrieved from the curated corpus, not model-invented."""

    alert_name: str | None
    source: str = Field(description="Repository-relative doc path and section anchor.")
    excerpt: str = Field(description="A short, verbatim excerpt grounding this citation.")


class TokenUsage(BaseModel):
    input_tokens: int
    output_tokens: int
    total_tokens: int


class IncidentSummary(BaseModel):
    """Structured incident-summary output. Never includes a remediation-performed field -
    this assistant has no tools and cannot act; see docs/ai-operations-assistant.md."""

    summary: str
    evidence: list[str]
    confidence: Confidence
    uncertainty: str = Field(description="What remains unknown or unverified.")
    suggested_steps: list[str]
    cited_runbooks: list[RunbookCitation]

    provider: ProviderName
    latency_ms: float
    tokens: TokenUsage | None = None
    estimated_cost_usd: float = 0.0
