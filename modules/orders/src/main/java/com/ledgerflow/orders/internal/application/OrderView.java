package com.ledgerflow.orders.internal.application;

import com.ledgerflow.orders.internal.domain.Order;
import java.time.Instant;
import java.util.UUID;

public record OrderView(
    UUID orderId,
    String clientReference,
    long amountMinor,
    String currency,
    String status,
    Instant createdAt,
    Instant updatedAt) {

  public static OrderView from(Order order) {
    return new OrderView(
        order.orderId(),
        order.clientReference(),
        order.amount().amountMinor(),
        order.amount().currency(),
        order.status().name(),
        order.createdAt(),
        order.updatedAt());
  }
}
