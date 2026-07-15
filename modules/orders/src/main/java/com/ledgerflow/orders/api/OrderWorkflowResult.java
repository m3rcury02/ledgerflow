package com.ledgerflow.orders.api;

public record OrderWorkflowResult(
    PublicOrder order, int responseStatus, String location, boolean replayed) {}
