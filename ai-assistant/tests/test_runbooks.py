from ai_assistant.runbooks import CORPUS, retrieve


def test_corpus_has_sixteen_entries():
    assert len(CORPUS) == 16


def test_corpus_alert_names_are_unique():
    names = [entry.alert_name for entry in CORPUS]
    assert len(names) == len(set(names))


def test_exact_alert_name_match_wins():
    result = retrieve("LedgerFlowApiFailureRate", "something unrelated", None)
    assert len(result) == 1
    assert result[0].alert_name == "LedgerFlowApiFailureRate"


def test_exact_alert_name_match_case_insensitive():
    result = retrieve("ledgerflowoutboxbacklog", "irrelevant", None)
    assert len(result) == 1
    assert result[0].alert_name == "LedgerFlowOutboxBacklog"


def test_unknown_alert_name_falls_back_to_keyword_retrieval():
    result = retrieve(
        "SomeUnknownAlert",
        "database pool exhausted, pending borrowers rising, readiness up",
        None,
    )
    assert any(e.alert_name == "LedgerFlowDatabasePoolExhaustion" for e in result)


def test_keyword_retrieval_by_description_only():
    result = retrieve(None, "Kafka consumer lag is rising and notifications are delayed", None)
    assert any(e.alert_name == "LedgerFlowKafkaConsumerLag" for e in result)


def test_keyword_retrieval_uses_telemetry_excerpt_too():
    result = retrieve(
        None,
        "worried about an incident",
        "circuit breaker open, provider timeouts, half-open probe failing",
    )
    assert any(e.alert_name == "LedgerFlowProviderCircuitOpen" for e in result)


def test_no_confident_match_returns_empty():
    result = retrieve(None, "asdf qwer zxcv", None)
    assert result == []


def test_retrieval_respects_limit():
    result = retrieve(None, "kafka outbox notification dlt consumer lag database", None, limit=2)
    assert len(result) <= 2


def test_every_entry_has_a_valid_source_anchor():
    for entry in CORPUS:
        assert entry.source == f"docs/observability-runbook.md#{entry.alert_name.lower()}"
