package com.ledgerflow.payments.internal.provider;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledgerflow.payment.provider")
public record PaymentProviderProperties(
    URI baseUrl,
    Duration connectTimeout,
    Duration readTimeout,
    Duration overallTimeout,
    Duration activeOperationTimeout,
    int maxAttempts,
    Duration baseBackoff,
    Duration maxBackoff,
    double backoffMultiplier,
    double jitterRatio,
    int circuitFailureThreshold,
    int circuitSlidingWindowSize,
    Duration circuitOpenDuration,
    int circuitHalfOpenCalls,
    int maxConcurrentCalls) {

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
    if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
      throw new IllegalArgumentException("provider read timeout must be positive");
    }
    if (overallTimeout == null || overallTimeout.isNegative() || overallTimeout.isZero()) {
      throw new IllegalArgumentException("provider overall timeout must be positive");
    }
    if (overallTimeout.compareTo(readTimeout) < 0 || overallTimeout.compareTo(connectTimeout) < 0) {
      throw new IllegalArgumentException(
          "provider overall timeout must not be less than connect or read timeout");
    }
    if (activeOperationTimeout == null || activeOperationTimeout.compareTo(overallTimeout) < 0) {
      throw new IllegalArgumentException(
          "provider active operation timeout must not be less than the overall timeout");
    }
    if (circuitFailureThreshold < 1 || circuitFailureThreshold > 100) {
      throw new IllegalArgumentException("circuitFailureThreshold must be between 1 and 100");
    }
    if (circuitSlidingWindowSize < circuitFailureThreshold || circuitSlidingWindowSize > 100) {
      throw new IllegalArgumentException(
          "circuitSlidingWindowSize must include the failure threshold and be at most 100");
    }
    if (circuitOpenDuration == null
        || circuitOpenDuration.isNegative()
        || circuitOpenDuration.isZero()) {
      throw new IllegalArgumentException("circuitOpenDuration must be positive");
    }
    if (circuitHalfOpenCalls < 1 || circuitHalfOpenCalls > 20) {
      throw new IllegalArgumentException("circuitHalfOpenCalls must be between 1 and 20");
    }
    if (maxConcurrentCalls < 1 || maxConcurrentCalls > 100) {
      throw new IllegalArgumentException("maxConcurrentCalls must be between 1 and 100");
    }
  }
}
