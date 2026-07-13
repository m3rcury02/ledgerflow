package com.ledgerflow.orders.internal.application;

import com.ledgerflow.orders.internal.domain.Money;
import com.ledgerflow.orders.internal.domain.Order;
import java.util.Optional;
import java.util.UUID;

public interface OrderStore {

  IdempotencyRecord claimIdempotencyKey(
      String principalScope, String operation, byte[] keyHash, byte[] requestHash);

  Order insertOrder(
      String ownerSubject, String clientReference, Money amount, String correlationId);

  void completeIdempotencyKey(
      String principalScope,
      String operation,
      byte[] keyHash,
      UUID resourceId,
      int responseStatus,
      String responseLocation,
      String responseBody);

  Optional<Order> findOwnedOrder(UUID orderId, String ownerSubject);
}
