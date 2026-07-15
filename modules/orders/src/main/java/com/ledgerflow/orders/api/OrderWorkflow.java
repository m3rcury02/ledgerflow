package com.ledgerflow.orders.api;

import java.util.UUID;

public interface OrderWorkflow {

  OrderWorkflowResult create(CreateOrderWorkflow command);

  PublicOrder get(UUID orderId, String ownerSubject);
}
