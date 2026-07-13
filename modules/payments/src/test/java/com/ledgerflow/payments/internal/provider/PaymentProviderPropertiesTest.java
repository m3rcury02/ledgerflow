package com.ledgerflow.payments.internal.provider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PaymentProviderPropertiesTest {

  @Test
  void rejectsAProviderUrlThatCouldEmbedCredentialsOrUseANonHttpScheme() {
    assertThatThrownBy(() -> properties("file:///etc/passwd"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTP(S)");
    assertThatThrownBy(() -> properties("https://user@example.test"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("credentials");
  }

  private PaymentProviderProperties properties(String baseUrl) {
    return new PaymentProviderProperties(
        URI.create(baseUrl),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        2,
        Duration.ofMillis(100),
        Duration.ofSeconds(1),
        2.0,
        0.2);
  }
}
