package com.ledgerflow.operations.internal.recovery;

import java.time.Instant;

record OperationAttempt(
    String source,
    String action,
    int attemptNumber,
    String outcome,
    String failureCode,
    Instant at) {}
