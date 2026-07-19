"""Orchestration: sanitize -> retrieve -> summarize. The only place a provider is constructed
from settings."""

from __future__ import annotations

from ai_assistant.config import Settings, get_settings
from ai_assistant.models import IncidentRequest, IncidentSummary
from ai_assistant.providers.base import Provider
from ai_assistant.providers.fake import FakeProvider
from ai_assistant.runbooks import retrieve
from ai_assistant.sanitizer import sanitize


class UnknownProviderError(ValueError):
    pass


def build_provider(settings: Settings) -> Provider:
    if settings.provider == "fake":
        return FakeProvider()
    if settings.provider == "openai":
        # Imported lazily so importing this module never requires the `openai` package to be
        # configured with a key when running with the (default) fake provider.
        from ai_assistant.providers.openai_provider import OpenAIProvider

        return OpenAIProvider(settings)
    raise UnknownProviderError(
        f"Unknown AI_ASSISTANT_PROVIDER {settings.provider!r}; expected 'fake' or 'openai'."
    )


def summarize_incident(
    request: IncidentRequest, settings: Settings | None = None
) -> IncidentSummary:
    settings = settings or get_settings()
    provider = build_provider(settings)

    sanitized = sanitize(request)
    retrieved = retrieve(sanitized.alert_name, sanitized.description, sanitized.telemetry_excerpt)
    return provider.summarize(sanitized, retrieved)
