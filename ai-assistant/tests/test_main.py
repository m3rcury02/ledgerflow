from fastapi.testclient import TestClient

from ai_assistant.main import app

client = TestClient(app)


def test_healthz():
    response = client.get("/healthz")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "provider": "fake"}


def test_list_runbooks_returns_all_sixteen():
    response = client.get("/v1/runbooks")
    assert response.status_code == 200
    body = response.json()
    assert len(body) == 16
    assert any(r["alert_name"] == "LedgerFlowApiFailureRate" for r in body)


def test_summarize_with_fake_provider():
    response = client.post(
        "/v1/incidents/summarize",
        json={
            "alert_name": "LedgerFlowOutboxBacklog",
            "description": "Outbox oldest age climbing past 60s",
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["provider"] == "fake"
    assert body["confidence"] == "high"
    assert body["cited_runbooks"][0]["alert_name"] == "LedgerFlowOutboxBacklog"


def test_summarize_rejects_empty_description():
    response = client.post("/v1/incidents/summarize", json={"description": ""})
    assert response.status_code == 422


def test_summarize_with_no_matching_runbook():
    response = client.post(
        "/v1/incidents/summarize", json={"description": "asdf qwer zxcv nonsense"}
    )
    assert response.status_code == 200
    body = response.json()
    assert body["confidence"] == "low"
    assert body["cited_runbooks"] == []
