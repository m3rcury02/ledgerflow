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

  @Test
  void rejectsAnOverallTimeoutShorterThanAComponentDeadline() {
    assertThatThrownBy(
            () ->
                properties(
                    "https://provider.example",
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(1),
                    Duration.ofMillis(1500)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("overall timeout");
  }

  private PaymentProviderProperties properties(String baseUrl) {
    return properties(baseUrl, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3));
  }

  private PaymentProviderProperties properties(
      String baseUrl, Duration connectTimeout, Duration readTimeout, Duration overallTimeout) {
    return new PaymentProviderProperties(
        URI.create(baseUrl),
        connectTimeout,
        readTimeout,
        overallTimeout,
        2,
        Duration.ofMillis(100),
        Duration.ofSeconds(1),
        2.0,
        0.2,
        3,
        3,
        Duration.ofSeconds(10),
        1,
        16);
  }
}
