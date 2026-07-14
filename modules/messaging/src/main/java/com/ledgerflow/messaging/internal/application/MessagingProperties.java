package com.ledgerflow.messaging.internal.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ledgerflow.messaging")
public record MessagingProperties(
    @DefaultValue("ledgerflow.payment-captured.v1") String topic,
    @DefaultValue("ledgerflow.payment-captured.v1.dlt") String deadLetterTopic,
    @DefaultValue("25") int batchSize,
    @DefaultValue("30s") Duration leaseDuration,
    @DefaultValue("10s") Duration acknowledgementTimeout,
    @DefaultValue("10") int maxPublishAttempts,
    @DefaultValue("1s") Duration publisherBaseBackoff,
    @DefaultValue("256s") Duration publisherMaxBackoff,
    @DefaultValue("0.2") double publisherJitterRatio) {

  public MessagingProperties {
    if (topic == null || topic.isBlank() || deadLetterTopic == null || deadLetterTopic.isBlank()) {
      throw new IllegalArgumentException("Kafka topic names must not be blank");
    }
    if (batchSize < 1 || batchSize > 500) {
      throw new IllegalArgumentException("outbox batchSize must be between 1 and 500");
    }
    if (leaseDuration == null
        || leaseDuration.isNegative()
        || leaseDuration.isZero()
        || acknowledgementTimeout == null
        || acknowledgementTimeout.isNegative()
        || acknowledgementTimeout.isZero()) {
      throw new IllegalArgumentException(
          "outbox lease and acknowledgement timeouts must be positive");
    }
    if (leaseDuration.compareTo(acknowledgementTimeout) <= 0) {
      throw new IllegalArgumentException("outbox lease must exceed the acknowledgement timeout");
    }
    if (maxPublishAttempts < 1 || maxPublishAttempts > 20) {
      throw new IllegalArgumentException("maxPublishAttempts must be between 1 and 20");
    }
    if (publisherBaseBackoff == null
        || publisherBaseBackoff.isNegative()
        || publisherBaseBackoff.isZero()
        || publisherMaxBackoff == null
        || publisherMaxBackoff.compareTo(publisherBaseBackoff) < 0) {
      throw new IllegalArgumentException("publisher backoff settings are invalid");
    }
    if (publisherJitterRatio < 0.0 || publisherJitterRatio > 1.0) {
      throw new IllegalArgumentException("publisherJitterRatio must be between 0 and 1");
    }
  }
}
