"""Provider base class.

`summarize()` is the only public entry point and is not overridden by subclasses - it enforces
that its argument is genuinely a `SanitizedIncidentRequest` (an `isinstance` check, not a type
hint alone) before handing off to `_summarize_sanitized()`, which concrete providers implement.
This is what makes "a provider only ever sees sanitized data" a tested runtime property instead
of a convention someone could forget to follow at a call site.
"""

from __future__ import annotations

from abc import ABC, abstractmethod

from ai_assistant.models import IncidentSummary, SanitizedIncidentRequest
from ai_assistant.runbooks import RunbookEntry


class UnsanitizedRequestError(TypeError):
    pass


class Provider(ABC):
    name: str

    def summarize(
        self, sanitized: SanitizedIncidentRequest, retrieved: list[RunbookEntry]
    ) -> IncidentSummary:
        if not isinstance(sanitized, SanitizedIncidentRequest):
            raise UnsanitizedRequestError(
                f"{type(self).__name__}.summarize() requires a SanitizedIncidentRequest, "
                f"got {type(sanitized).__name__}. Call sanitizer.sanitize() first."
            )
        return self._summarize_sanitized(sanitized, retrieved)

    @abstractmethod
    def _summarize_sanitized(
        self, sanitized: SanitizedIncidentRequest, retrieved: list[RunbookEntry]
    ) -> IncidentSummary: ...
