package com.ledgerflow.notifications.internal.kafka;

import com.ledgerflow.messaging.api.EventEnvelopeCodec;
import com.ledgerflow.notifications.api.DeadLetterReplay;
import com.ledgerflow.notifications.internal.application.DeadLetterReplayService;
import com.ledgerflow.notifications.internal.application.NotificationIntegrityException;
import com.ledgerflow.notifications.internal.application.NotificationValidationException;
import com.ledgerflow.notifications.internal.application.NotificationsProperties;
import com.ledgerflow.notifications.internal.persistence.JdbcNotificationStore;
import com.ledgerflow.operations.api.FaultInjection;
import com.ledgerflow.operations.api.WorkTracker;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerPausingBackOffHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerContainerPauseService;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableConfigurationProperties(NotificationsProperties.class)
@ConditionalOnProperty(prefix = "ledgerflow.notifications", name = "enabled", havingValue = "true")
public class NotificationsConfiguration {

  @Bean("notificationClock")
  Clock notificationClock() {
    return Clock.systemUTC();
  }

  @Bean
  NotificationEventValidator notificationEventValidator(EventEnvelopeCodec codec) {
    return new NotificationEventValidator(codec);
  }

  @Bean
  DeadLetterInputClassifier deadLetterInputClassifier() {
    return new DeadLetterInputClassifier();
  }

  @Bean
  NotificationMetrics notificationMetrics(MeterRegistry meterRegistry) {
    return new NotificationMetrics(meterRegistry);
  }

  @Bean
  JdbcNotificationStore jdbcNotificationStore(JdbcClient jdbcClient) {
    return new JdbcNotificationStore(jdbcClient);
  }

  @Bean
  PaymentCapturedKafkaListener paymentCapturedKafkaListener(
      NotificationEventValidator validator,
      JdbcNotificationStore store,
      NotificationsProperties properties,
      @Qualifier("notificationClock") Clock clock,
      WorkTracker workTracker,
      FaultInjection faultInjection,
      NotificationMetrics metrics,
      OpenTelemetry openTelemetry) {
    return new PaymentCapturedKafkaListener(
        validator, store, properties, clock, workTracker, faultInjection, metrics, openTelemetry);
  }

  @Bean
  DeadLetterReplay deadLetterReplayService(
      JdbcNotificationStore store,
      NotificationEventValidator validator,
      KafkaTemplate<String, String> kafkaTemplate,
      NotificationsProperties properties,
      ObjectProvider<OpenTelemetry> openTelemetryProvider,
      @Qualifier("notificationClock") Clock clock) {
    OpenTelemetry openTelemetry = openTelemetryProvider.getIfAvailable(GlobalOpenTelemetry::get);
    return new DeadLetterReplayService(
        store, validator, kafkaTemplate, properties, openTelemetry, clock);
  }

  @Bean("notificationKafkaListenerContainerFactory")
  ConcurrentKafkaListenerContainerFactory<String, String> notificationKafkaListenerContainerFactory(
      ConsumerFactory<String, String> consumerFactory,
      KafkaTemplate<String, String> kafkaTemplate,
      NotificationsProperties properties,
      KafkaListenerEndpointRegistry registry,
      @Qualifier("notificationRetryTaskScheduler") TaskScheduler retryScheduler,
      NotificationMetrics metrics) {
    requireManualCommit(consumerFactory);
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        baseFactory(consumerFactory, properties);
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, exception) ->
                new TopicPartition(properties.deadLetterTopic(), record.partition()));
    recoverer.setFailIfSendResultIsError(true);
    recoverer.setThrowIfNoDestinationReturned(true);
    recoverer.setWaitForSendResultTimeout(properties.brokerAcknowledgementTimeout());
    recoverer.setAppendOriginalHeaders(false);
    recoverer.setStripPreviousExceptionHeaders(true);
    recoverer.excludeHeader(
        DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd.EX_MSG,
        DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd.EX_STACKTRACE);
    recoverer.addHeadersFunction(
        (record, exception) -> {
          RecordHeaders headers = new RecordHeaders();
          int attempt = KafkaEventHeaders.deliveryAttempt(record.headers());
          headers.add(
              KafkaEventHeaders.LEDGERFLOW_DELIVERY_ATTEMPT,
              Integer.toString(attempt).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
          return headers;
        });

    DefaultErrorHandler errorHandler =
        new DefaultErrorHandler(
            recoverer,
            new RetrySequenceBackOff(
                properties.firstRetryBackoff(),
                properties.secondRetryBackoff(),
                properties.thirdRetryBackoff()),
            new ContainerPausingBackOffHandler(
                new ListenerContainerPauseService(registry, retryScheduler)));
    errorHandler.addNotRetryableExceptions(
        NotificationValidationException.class, NotificationIntegrityException.class);
    errorHandler.setRetryListeners(notificationRetryListener(metrics));
    factory.setCommonErrorHandler(errorHandler);
    return factory;
  }

  @Bean("notificationDltKafkaListenerContainerFactory")
  @ConditionalOnProperty(
      prefix = "ledgerflow.notifications",
      name = "dlt-enabled",
      havingValue = "true")
  ConcurrentKafkaListenerContainerFactory<String, String>
      notificationDltKafkaListenerContainerFactory(
          ConsumerFactory<String, String> consumerFactory,
          NotificationsProperties properties,
          KafkaListenerEndpointRegistry registry,
          @Qualifier("notificationRetryTaskScheduler") TaskScheduler retryScheduler) {
    requireManualCommit(consumerFactory);
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        baseFactory(consumerFactory, properties);
    DefaultErrorHandler errorHandler =
        new DefaultErrorHandler(
            (record, exception) -> {
              throw new DeadLetterCatalogUnavailableException(exception);
            },
            new RetrySequenceBackOff(
                properties.firstRetryBackoff(),
                properties.secondRetryBackoff(),
                properties.thirdRetryBackoff()),
            new ContainerPausingBackOffHandler(
                new ListenerContainerPauseService(registry, retryScheduler)));
    errorHandler.setAckAfterHandle(false);
    factory.setCommonErrorHandler(errorHandler);
    return factory;
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "ledgerflow.notifications",
      name = "dlt-enabled",
      havingValue = "true")
  DeadLetterCatalogListener deadLetterCatalogListener(
      NotificationEventValidator validator,
      DeadLetterInputClassifier inputClassifier,
      EventEnvelopeCodec codec,
      JdbcNotificationStore store,
      NotificationsProperties properties,
      ObjectMapper objectMapper,
      NotificationMetrics metrics,
      @Qualifier("notificationClock") Clock clock) {
    return new DeadLetterCatalogListener(
        validator, inputClassifier, codec, store, properties, objectMapper, metrics, clock);
  }

  @Bean("notificationRetryTaskScheduler")
  TaskScheduler notificationRetryTaskScheduler(
      NotificationsProperties properties, MeterRegistry meterRegistry) {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(properties.concurrency());
    scheduler.setThreadNamePrefix("notification-retry-");
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationMillis(properties.shutdownTimeout().toMillis());
    io.micrometer.core.instrument.Gauge.builder(
            "ledgerflow.executor.active", scheduler, ThreadPoolTaskScheduler::getActiveCount)
        .description("Active tasks in bounded application executors")
        .tag("executor", "notification_retry")
        .register(meterRegistry);
    io.micrometer.core.instrument.Gauge.builder(
            "ledgerflow.executor.pool.size", scheduler, ThreadPoolTaskScheduler::getPoolSize)
        .description("Current bounded application executor pool size")
        .tag("executor", "notification_retry")
        .register(meterRegistry);
    return scheduler;
  }

  private RetryListener notificationRetryListener(NotificationMetrics metrics) {
    return new RetryListener() {
      @Override
      public void failedDelivery(
          org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record,
          Exception exception,
          int deliveryAttempt) {
        record.headers().remove(KafkaEventHeaders.LEDGERFLOW_DELIVERY_ATTEMPT);
        record
            .headers()
            .add(
                KafkaEventHeaders.LEDGERFLOW_DELIVERY_ATTEMPT,
                Integer.toString(deliveryAttempt)
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        if (deliveryAttempt > 1) {
          metrics.consumer(NotificationMetrics.ConsumerMetric.RETRY);
        }
      }

      @Override
      public void recovered(
          org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record, Exception exception) {
        metrics.consumer(NotificationMetrics.ConsumerMetric.DLT);
      }

      @Override
      public void recoveryFailed(
          org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record,
          Exception original,
          Exception failure) {
        metrics.consumer(NotificationMetrics.ConsumerMetric.FAILURE);
      }
    };
  }

  private ConcurrentKafkaListenerContainerFactory<String, String> baseFactory(
      ConsumerFactory<String, String> consumerFactory, NotificationsProperties properties) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.setConcurrency(properties.concurrency());
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
    factory.getContainerProperties().setDeliveryAttemptHeader(true);
    factory.getContainerProperties().setObservationEnabled(true);
    factory.getContainerProperties().setPauseImmediate(true);
    factory.getContainerProperties().setStopImmediate(false);
    factory.getContainerProperties().setShutdownTimeout(properties.shutdownTimeout().toMillis());
    factory.getContainerProperties().setPollTimeoutWhilePaused(java.time.Duration.ofMillis(100));
    return factory;
  }

  private void requireManualCommit(ConsumerFactory<String, String> consumerFactory) {
    if (consumerFactory.isAutoCommit()) {
      throw new IllegalStateException(
          "ledgerflow notifications require Kafka enable.auto.commit=false");
    }
  }
}
