package com.ledgerflow.ledger.api;

import java.util.UUID;

public record PostPaymentCaptureCommand(UUID paymentId, String correlationId, String actor) {}
