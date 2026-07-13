package com.ledgerflow.orders.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class IdempotencyKeyTest {

  @Test
  void validatesAndHashesAnOpaqueKey() {
    IdempotencyKey key = new IdempotencyKey("order-client_0001");

    assertThat(key.sha256()).hasSize(32);
    assertThat(key.sha256()).isNotEqualTo("order-client_0001".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void rejectsInvalidLengthAndCharacters() {
    assertThatThrownBy(() -> new IdempotencyKey("short"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IdempotencyKey("contains spaces"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IdempotencyKey("a".repeat(129)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
