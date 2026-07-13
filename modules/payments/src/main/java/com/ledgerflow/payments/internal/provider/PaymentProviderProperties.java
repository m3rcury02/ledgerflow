package com.ledgerflow.payments.internal.provider;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledgerflow.payment.provider")
public record PaymentProviderProperties(
    URI baseUrl,
    Duration connectTimeout,
    Duration requestTimeout,
    int maxAttempts,
    Duration baseBackoff,
    Duration maxBackoff,
    double backoffMultiplier,
    double jitterRatio) {

  public PaymentProviderProperties {
    if (baseUrl == null
        || !baseUrl.isAbsolute()
        || !("http".equalsIgnoreCase(baseUrl.getScheme())
            || "https".equalsIgnoreCase(baseUrl.getScheme()))
        || baseUrl.getHost() == null
        || !(baseUrl.getPath().isEmpty() || "/".equals(baseUrl.getPath()))
        || baseUrl.getUserInfo() != null
        || baseUrl.getQuery() != null
        || baseUrl.getFragment() != null) {
      throw new IllegalArgumentException(
          "payment provider base URL must be an absolute HTTP(S) origin "
              + "without credentials, query, or fragment");
    }
    if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
      throw new IllegalArgumentException("provider connect timeout must be positive");
    }
    if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
      throw new IllegalArgumentException("provider request timeout must be positive");
    }
  }
}
