package com.ledgerflow.payments.internal.domain;

import java.time.Instant;
import java.util.UUID;

public record AttemptHistory(
    PaymentStage stage,
    AttemptActivity activity,
    int attemptNumber,
    AttemptOutcome outcome,
    UUID providerRequestId,
    String providerReference,
    String failureCode,
    String correlationId,
    Instant recordedAt) {}
