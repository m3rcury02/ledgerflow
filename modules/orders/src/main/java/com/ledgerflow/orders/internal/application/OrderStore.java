package com.ledgerflow.orders.internal.application;

import com.ledgerflow.orders.internal.domain.Money;
import com.ledgerflow.orders.internal.domain.Order;
import com.ledgerflow.orders.internal.domain.OrderStatus;
import java.util.Optional;
import java.util.UUID;

public interface OrderStore {

  IdempotencyRecord claimIdempotencyKey(
      String principalScope, String operation, byte[] keyHash, byte[] requestHash);

  Order insertOrder(
      String ownerSubject, String clientReference, Money amount, String correlationId);

  Order insertWorkflowOrder(
      String ownerSubject, String clientReference, Money amount, String correlationId);

  void attachIdempotencyResource(
      String principalScope, String operation, byte[] keyHash, UUID resourceId);

  IdempotencyRecord lockIdempotencyKey(String principalScope, String operation, byte[] keyHash);

  void completeIdempotencyKey(
      String principalScope,
      String operation,
      byte[] keyHash,
      UUID resourceId,
      int responseStatus,
      String responseLocation,
      String responseBody);

  Optional<Order> findOwnedOrder(UUID orderId, String ownerSubject);

  Optional<Order> findOrder(UUID orderId);

  Order transitionOrder(UUID orderId, OrderStatus expected, OrderStatus target);
}
