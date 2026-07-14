package com.ledgerflow.operations.api;

/** A single in-flight operation. Closing a token must be idempotent. */
@FunctionalInterface
public interface WorkToken extends AutoCloseable {

  @Override
  void close();
}
