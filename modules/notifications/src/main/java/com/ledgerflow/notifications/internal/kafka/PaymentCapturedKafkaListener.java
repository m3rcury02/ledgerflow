package com.ledgerflow.notifications.internal.kafka;

import com.ledgerflow.notifications.internal.application.NotificationsProperties;
import com.ledgerflow.notifications.internal.persistence.JdbcNotificationStore;
import java.time.Clock;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

final class PaymentCapturedKafkaListener {

  private final NotificationEventValidator validator;
  private final JdbcNotificationStore store;
  private final NotificationsProperties properties;
  private final Clock clock;

  PaymentCapturedKafkaListener(
      NotificationEventValidator validator,
      JdbcNotificationStore store,
      NotificationsProperties properties,
      Clock clock) {
    this.validator = validator;
    this.store = store;
    this.properties = properties;
    this.clock = clock;
  }

  @KafkaListener(
      id = "ledgerflow-notification-consumer",
      topics = "${ledgerflow.notifications.topic:ledgerflow.payment-captured.v1}",
      groupId = "${ledgerflow.notifications.group-id:ledgerflow-notifications-v1}",
      containerFactory = "notificationKafkaListenerContainerFactory")
  void onPaymentCaptured(ConsumerRecord<String, String> record) {
    ValidatedNotificationEvent validated = validator.validateMain(record, properties.topic());
    store.process(
        validated.event(),
        validated.canonicalPayloadHash(),
        record.topic(),
        record.partition(),
        record.offset(),
        validated.processingCorrelationId(),
        clock.instant());
  }
}
