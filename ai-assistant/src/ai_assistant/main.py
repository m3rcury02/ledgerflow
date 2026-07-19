from __future__ import annotations

from fastapi import FastAPI, HTTPException

from ai_assistant.config import get_settings
from ai_assistant.models import IncidentRequest, IncidentSummary
from ai_assistant.runbooks import CORPUS
from ai_assistant.service import UnknownProviderError, summarize_incident

app = FastAPI(
    title="LedgerFlow AI Operations Assistant",
    description=(
        "Optional incident-summary assistant. Advisory only - see docs/ai-operations-"
        "assistant.md, 'No automatic remediation'."
    ),
    version="0.1.0",
)


@app.get("/healthz")
def healthz() -> dict:
    settings = get_settings()
    return {"status": "ok", "provider": settings.provider}


@app.get("/v1/runbooks")
def list_runbooks() -> list[dict]:
    return [
        {"alert_name": entry.alert_name, "source": entry.source, "impact": entry.impact}
        for entry in CORPUS
    ]


@app.post("/v1/incidents/summarize", response_model=IncidentSummary)
def summarize(request: IncidentRequest) -> IncidentSummary:
    try:
        return summarize_incident(request)
    except UnknownProviderError as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    except ValueError as exc:
        # Covers CostCeilingExceededError (a RuntimeError, not ValueError - see below) plus
        # provider misconfiguration (e.g. missing API key) and malformed model output.
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
