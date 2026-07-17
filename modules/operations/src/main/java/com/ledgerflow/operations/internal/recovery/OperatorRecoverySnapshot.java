package com.ledgerflow.operations.internal.recovery;

record OperatorRecoverySnapshot(
    long pending, long inProgress, long waiting, long failed, long oldestActiveAgeSeconds) {}
