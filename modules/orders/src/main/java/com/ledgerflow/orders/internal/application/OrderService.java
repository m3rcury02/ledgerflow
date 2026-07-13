package com.ledgerflow.orders.internal.application;

import com.ledgerflow.orders.internal.domain.Order;
import com.ledgerflow.orders.internal.domain.RequestFingerprint;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderService {

  private static final String CREATE_OPERATION = "CREATE_ORDER_V1";

  private final OrderStore orderStore;
  private final ObjectMapper objectMapper;

  public OrderService(OrderStore orderStore, ObjectMapper objectMapper) {
    this.orderStore = orderStore;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public CreateOrderResult create(CreateOrderCommand command) {
    byte[] keyHash = command.idempotencyKey().sha256();
    byte[] requestHash = RequestFingerprint.create(command.clientReference(), command.amount());
    IdempotencyRecord record =
        orderStore.claimIdempotencyKey(
            command.ownerSubject(), CREATE_OPERATION, keyHash, requestHash);

    if (!MessageDigest.isEqual(requestHash, record.requestHash())) {
      throw new IdempotencyConflictException();
    }

    if (!record.claimed()) {
      return replay(record);
    }

    Order order =
        orderStore.insertOrder(
            command.ownerSubject(),
            command.clientReference(),
            command.amount(),
            command.correlationId());
    OrderView view = OrderView.from(order);
    String location = "/api/v1/orders/" + order.orderId();
    orderStore.completeIdempotencyKey(
        command.ownerSubject(),
        CREATE_OPERATION,
        keyHash,
        order.orderId(),
        201,
        location,
        serialize(view));
    return new CreateOrderResult(view, location, false);
  }

  @Transactional(readOnly = true)
  public OrderView get(UUID orderId, String ownerSubject) {
    return orderStore
        .findOwnedOrder(orderId, ownerSubject)
        .map(OrderView::from)
        .orElseThrow(OrderNotFoundException::new);
  }

  private CreateOrderResult replay(IdempotencyRecord record) {
    if (!"COMPLETED".equals(record.state())
        || record.resourceId() == null
        || record.responseStatus() == null
        || record.responseStatus() != 201
        || record.responseLocation() == null
        || record.responseBody() == null) {
      throw new IdempotencyUnavailableException();
    }
    ResponseSnapshot snapshot = deserialize(record.responseBody());
    if (!record.resourceId().equals(snapshot.orderId())) {
      throw new IdempotencyUnavailableException();
    }
    return new CreateOrderResult(snapshot.toOrderView(), record.responseLocation(), true);
  }

  private String serialize(OrderView order) {
    try {
      return objectMapper.writeValueAsString(ResponseSnapshot.from(order));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Could not serialize the idempotent response", exception);
    }
  }

  private ResponseSnapshot deserialize(String responseBody) {
    try {
      return objectMapper.readValue(responseBody, ResponseSnapshot.class);
    } catch (JacksonException exception) {
      throw new IdempotencyUnavailableException();
    }
  }

  private record ResponseSnapshot(
      UUID orderId,
      String clientReference,
      String status,
      MoneySnapshot amount,
      Instant createdAt,
      Instant updatedAt) {

    static ResponseSnapshot from(OrderView order) {
      return new ResponseSnapshot(
          order.orderId(),
          order.clientReference(),
          order.status(),
          new MoneySnapshot(order.amountMinor(), order.currency()),
          order.createdAt(),
          order.updatedAt());
    }

    OrderView toOrderView() {
      return new OrderView(
          orderId,
          clientReference,
          amount.amountMinor,
          amount.currency,
          status,
          createdAt,
          updatedAt);
    }
  }

  private record MoneySnapshot(long amountMinor, String currency) {}
}
