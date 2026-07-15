package com.ledgerflow.orders.internal.domain;

public enum OrderStatus {
  CREATED,
  PAYMENT_PROCESSING,
  COMPLETED,
  PAYMENT_DECLINED,
  PAYMENT_RETRY_PENDING,
  FAILED
}
