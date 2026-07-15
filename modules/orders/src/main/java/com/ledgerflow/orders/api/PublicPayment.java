package com.ledgerflow.orders.api;

import java.util.UUID;

public record PublicPayment(UUID paymentId, String status, String failureCode) {}
