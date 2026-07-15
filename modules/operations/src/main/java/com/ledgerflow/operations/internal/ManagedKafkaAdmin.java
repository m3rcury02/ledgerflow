package com.ledgerflow.operations.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.kafka.clients.admin.Admin;

final class ManagedKafkaAdmin implements AutoCloseable {

  private final Supplier<Admin> clientFactory;
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile Admin client;

  ManagedKafkaAdmin(Supplier<Admin> clientFactory) {
    this.clientFactory =
        Objects.requireNonNull(clientFactory, "Kafka Admin client factory must not be null");
  }

  Admin client() {
    if (closed.get()) {
      throw new IllegalStateException("Kafka Admin client is closed");
    }
    Admin current = client;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (closed.get()) {
        throw new IllegalStateException("Kafka Admin client is closed");
      }
      if (client == null) {
        client = Objects.requireNonNull(clientFactory.get(), "Kafka Admin client must not be null");
      }
      return client;
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      Admin current = client;
      if (current != null) {
        current.close(Duration.ZERO);
      }
    }
  }
}
