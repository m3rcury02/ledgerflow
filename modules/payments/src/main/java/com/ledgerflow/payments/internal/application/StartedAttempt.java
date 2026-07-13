package com.ledgerflow.payments.internal.application;

import com.ledgerflow.payments.internal.domain.Payment;

public record StartedAttempt(Payment payment, int attemptNumber) {}
