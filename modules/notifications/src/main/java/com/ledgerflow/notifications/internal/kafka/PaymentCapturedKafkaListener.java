package com.ledgerflow.notifications.internal.kafka;

import com.ledgerflow.notifications.internal.application.NotificationIntegrityException;
import com.ledgerflow.notifications.internal.application.NotificationSemanticConflictException;
import com.ledgerflow.notifications.internal.application.NotificationsProperties;
import com.ledgerflow.notifications.internal.persistence.JdbcNotificationStore;
import com.ledgerflow.notifications.internal.persistence.NotificationProcessOutcome;
import com.ledgerflow.operations.api.FaultInjection;
import com.ledgerflow.operations.api.FaultPoint;
import com.ledgerflow.operations.api.WorkToken;
import com.ledgerflow.operations.api.WorkTracker;
import java.time.Clock;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

final class PaymentCapturedKafkaListener {

  private final NotificationEventValidator validator;
  private final JdbcNotificationStore store;
  private final NotificationsProperties properties;
  private final Clock clock;
  private final WorkTracker workTracker;
  private final FaultInjection faultInjection;
  private final NotificationMetrics metrics;

  PaymentCapturedKafkaListener(
      NotificationEventValidator validator,
      JdbcNotificationStore store,
      NotificationsProperties properties,
      Clock clock,
      WorkTracker workTracker,
      FaultInjection faultInjection,
      NotificationMetrics metrics) {
    this.validator = validator;
    this.store = store;
    this.properties = properties;
    this.clock = clock;
    this.workTracker = workTracker;
    this.faultInjection = faultInjection;
    this.metrics = metrics;
  }

  @KafkaListener(
      id = "ledgerflow-notification-consumer",
      topics = "${ledgerflow.notifications.topic:ledgerflow.payment-captured.v1}",
      groupId = "${ledgerflow.notifications.group-id:ledgerflow-notifications-v1}",
      containerFactory = "notificationKafkaListenerContainerFactory")
  void onPaymentCaptured(ConsumerRecord<String, String> record) {
    WorkToken work = workTracker.begin("notification-consume");
    try {
      faultInjection.before(FaultPoint.NOTIFICATION_CONSUME);
      ValidatedNotificationEvent validated = validator.validateMain(record, properties.topic());
      try {
        NotificationProcessOutcome outcome =
            store.process(
                validated.event(),
                validated.effectIdentity(),
                validated.canonicalPayloadHash(),
                record.topic(),
                record.partition(),
                record.offset(),
                validated.processingCorrelationId(),
                clock.instant());
        metrics.processing(NotificationMetrics.ProcessingMetric.valueOf(outcome.name()));
      } catch (NotificationSemanticConflictException exception) {
        metrics.processing(NotificationMetrics.ProcessingMetric.SEMANTIC_CONFLICT);
        throw exception;
      } catch (NotificationIntegrityException exception) {
        metrics.processing(NotificationMetrics.ProcessingMetric.TRANSPORT_CONFLICT);
        throw exception;
      }
    } finally {
      work.close();
    }
  }
}
