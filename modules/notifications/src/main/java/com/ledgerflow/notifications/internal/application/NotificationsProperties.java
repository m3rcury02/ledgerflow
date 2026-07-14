package com.ledgerflow.notifications.internal.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ledgerflow.notifications")
public record NotificationsProperties(
    @DefaultValue("ledgerflow.payment-captured.v1") String topic,
    @DefaultValue("ledgerflow.payment-captured.v1.dlt") String deadLetterTopic,
    @DefaultValue("ledgerflow-notifications-v1") String groupId,
    @DefaultValue("ledgerflow-notifications-dlt-v1") String deadLetterGroupId,
    @DefaultValue("ledgerflow-notifications-v1") String consumerName,
    @DefaultValue("1s") Duration firstRetryBackoff,
    @DefaultValue("5s") Duration secondRetryBackoff,
    @DefaultValue("30s") Duration thirdRetryBackoff,
    @DefaultValue("10s") Duration brokerAcknowledgementTimeout,
    @DefaultValue("30s") Duration replayLeaseDuration,
    @DefaultValue("2") int concurrency,
    @DefaultValue("25") int maxPollRecords,
    @DefaultValue("20s") Duration shutdownTimeout) {

  public NotificationsProperties {
    requireTopic(topic, "topic");
    requireTopic(deadLetterTopic, "deadLetterTopic");
    requireSafeName(groupId, 255, "groupId");
    requireSafeName(deadLetterGroupId, 255, "deadLetterGroupId");
    requireSafeName(consumerName, 100, "consumerName");
    requirePositive(firstRetryBackoff, "firstRetryBackoff");
    requirePositive(secondRetryBackoff, "secondRetryBackoff");
    requirePositive(thirdRetryBackoff, "thirdRetryBackoff");
    requirePositive(brokerAcknowledgementTimeout, "brokerAcknowledgementTimeout");
    requirePositive(replayLeaseDuration, "replayLeaseDuration");
    requirePositive(shutdownTimeout, "shutdownTimeout");
    if (replayLeaseDuration.compareTo(brokerAcknowledgementTimeout) <= 0) {
      throw new IllegalArgumentException(
          "replayLeaseDuration must exceed brokerAcknowledgementTimeout");
    }
    if (concurrency < 1 || concurrency > 8) {
      throw new IllegalArgumentException("concurrency must be between 1 and 8");
    }
    if (maxPollRecords < 1 || maxPollRecords > 100) {
      throw new IllegalArgumentException("maxPollRecords must be between 1 and 100");
    }
  }

  private static void requireTopic(String value, String name) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,248}")) {
      throw new IllegalArgumentException(name + " is not a valid Kafka topic");
    }
  }

  private static void requireSafeName(String value, int maximumLength, String name) {
    String pattern = "[A-Za-z0-9][A-Za-z0-9._-]{0," + (maximumLength - 1) + "}";
    if (value == null || !value.matches(pattern)) {
      throw new IllegalArgumentException(name + " has an invalid format");
    }
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
