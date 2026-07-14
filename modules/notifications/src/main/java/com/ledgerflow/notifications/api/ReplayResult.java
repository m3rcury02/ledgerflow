package com.ledgerflow.notifications.api;

import java.util.UUID;

public record ReplayResult(
    UUID replayRequestId, ReplayOutcome outcome, String processingCorrelationId) {}
