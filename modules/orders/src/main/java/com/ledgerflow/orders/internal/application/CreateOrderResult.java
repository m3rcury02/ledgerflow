package com.ledgerflow.orders.internal.application;

public record CreateOrderResult(OrderView order, String location, boolean replayed) {}
