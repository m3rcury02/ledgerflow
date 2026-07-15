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
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
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
  private final OpenTelemetry openTelemetry;

  PaymentCapturedKafkaListener(
      NotificationEventValidator validator,
      JdbcNotificationStore store,
      NotificationsProperties properties,
      Clock clock,
      WorkTracker workTracker,
      FaultInjection faultInjection,
      NotificationMetrics metrics,
      OpenTelemetry openTelemetry) {
    this.validator = validator;
    this.store = store;
    this.properties = properties;
    this.clock = clock;
    this.workTracker = workTracker;
    this.faultInjection = faultInjection;
    this.metrics = metrics;
    this.openTelemetry = openTelemetry;
  }

  @KafkaListener(
      id = "ledgerflow-notification-consumer",
      topics = "${ledgerflow.notifications.topic:ledgerflow.payment-captured.v1}",
      groupId = "${ledgerflow.notifications.group-id:ledgerflow-notifications-v1}",
      containerFactory = "notificationKafkaListenerContainerFactory")
  void onPaymentCaptured(ConsumerRecord<String, String> record) {
    WorkToken work = workTracker.begin("notification-consume");
    Span span =
        openTelemetry
            .getTracer("com.ledgerflow.notifications")
            .spanBuilder("notification.process")
            .setSpanKind(SpanKind.CONSUMER)
            .setAttribute("messaging.system", "kafka")
            .setAttribute("db.system.name", "postgresql")
            .startSpan();
    Scope scope = span.makeCurrent();
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
        NotificationMetrics.ProcessingMetric processingMetric =
            NotificationMetrics.ProcessingMetric.valueOf(outcome.name());
        metrics.processing(processingMetric);
        metrics.processingDelay(
            processingMetric,
            java.time.Duration.between(validated.event().occurredAt(), clock.instant()));
        metrics.consumer(NotificationMetrics.ConsumerMetric.PROCESSED);
        span.setAttribute("ledgerflow.outcome", outcome.name().toLowerCase(java.util.Locale.ROOT));
      } catch (NotificationSemanticConflictException exception) {
        metrics.processing(NotificationMetrics.ProcessingMetric.SEMANTIC_CONFLICT);
        metrics.consumer(NotificationMetrics.ConsumerMetric.FAILURE);
        span.setStatus(StatusCode.ERROR, "semantic_conflict");
        throw exception;
      } catch (NotificationIntegrityException exception) {
        metrics.processing(NotificationMetrics.ProcessingMetric.TRANSPORT_CONFLICT);
        metrics.consumer(NotificationMetrics.ConsumerMetric.FAILURE);
        span.setStatus(StatusCode.ERROR, "transport_conflict");
        throw exception;
      }
    } catch (RuntimeException exception) {
      span.setStatus(StatusCode.ERROR, "notification_processing_failed");
      throw exception;
    } finally {
      scope.close();
      span.end();
      work.close();
    }
  }
}
