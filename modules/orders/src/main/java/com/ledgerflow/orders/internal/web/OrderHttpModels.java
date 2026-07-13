package com.ledgerflow.orders.internal.web;

import com.ledgerflow.orders.internal.application.OrderView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

final class OrderHttpModels {

  private OrderHttpModels() {
    // HTTP model namespace.
  }

  record CreateOrderRequest(
      @Size(min = 1, max = 100) @Pattern(regexp = "^\\S(?:.*\\S)?$") String clientReference,
      @NotNull @Valid MoneyRequest amount) {}

  record MoneyRequest(
      @Positive long amountMinor, @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {}

  record OrderResponse(
      UUID orderId,
      String clientReference,
      String status,
      MoneyResponse amount,
      Instant createdAt,
      Instant updatedAt) {

    static OrderResponse from(OrderView order) {
      return new OrderResponse(
          order.orderId(),
          order.clientReference(),
          order.status(),
          new MoneyResponse(order.amountMinor(), order.currency()),
          order.createdAt(),
          order.updatedAt());
    }
  }

  record MoneyResponse(long amountMinor, String currency) {}
}
