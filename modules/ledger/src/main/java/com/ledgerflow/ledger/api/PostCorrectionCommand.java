package com.ledgerflow.ledger.api;

import java.util.UUID;

public record PostCorrectionCommand(
    UUID originalTransactionId, String reason, String correlationId, String actor) {}
