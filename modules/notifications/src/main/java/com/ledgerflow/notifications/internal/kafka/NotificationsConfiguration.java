package com.ledgerflow.notifications.internal.kafka;

import com.ledgerflow.messaging.api.EventEnvelopeCodec;
import com.ledgerflow.notifications.api.DeadLetterReplay;
import com.ledgerflow.notifications.internal.application.DeadLetterReplayService;
import com.ledgerflow.notifications.internal.application.NotificationIntegrityException;
import com.ledgerflow.notifications.internal.application.NotificationValidationException;
import com.ledgerflow.notifications.internal.application.NotificationsProperties;
import com.ledgerflow.notifications.internal.persistence.JdbcNotificationStore;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
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
  JdbcNotificationStore jdbcNotificationStore(JdbcClient jdbcClient) {
    return new JdbcNotificationStore(jdbcClient);
  }

  @Bean
  PaymentCapturedKafkaListener paymentCapturedKafkaListener(
      NotificationEventValidator validator,
      JdbcNotificationStore store,
      NotificationsProperties properties,
      @Qualifier("notificationClock") Clock clock) {
    return new PaymentCapturedKafkaListener(validator, store, properties, clock);
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
      NotificationsProperties properties) {
    requireManualCommit(consumerFactory);
    ConcurrentKafkaListenerContainerFactory<String, String> factory = baseFactory(consumerFactory);
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

    DefaultErrorHandler errorHandler =
        new DefaultErrorHandler(
            recoverer,
            new RetrySequenceBackOff(
                properties.firstRetryBackoff(),
                properties.secondRetryBackoff(),
                properties.thirdRetryBackoff()));
    errorHandler.addNotRetryableExceptions(
        NotificationValidationException.class, NotificationIntegrityException.class);
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
          ConsumerFactory<String, String> consumerFactory, NotificationsProperties properties) {
    requireManualCommit(consumerFactory);
    ConcurrentKafkaListenerContainerFactory<String, String> factory = baseFactory(consumerFactory);
    DefaultErrorHandler errorHandler =
        new DefaultErrorHandler(
            (record, exception) -> {
              throw new DeadLetterCatalogUnavailableException(exception);
            },
            new RetrySequenceBackOff(
                properties.firstRetryBackoff(),
                properties.secondRetryBackoff(),
                properties.thirdRetryBackoff()));
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
      EventEnvelopeCodec codec,
      JdbcNotificationStore store,
      NotificationsProperties properties,
      ObjectMapper objectMapper,
      @Qualifier("notificationClock") Clock clock) {
    return new DeadLetterCatalogListener(validator, codec, store, properties, objectMapper, clock);
  }

  private ConcurrentKafkaListenerContainerFactory<String, String> baseFactory(
      ConsumerFactory<String, String> consumerFactory) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
    factory.getContainerProperties().setDeliveryAttemptHeader(true);
    factory.getContainerProperties().setObservationEnabled(true);
    return factory;
  }

  private void requireManualCommit(ConsumerFactory<String, String> consumerFactory) {
    if (consumerFactory.isAutoCommit()) {
      throw new IllegalStateException(
          "ledgerflow notifications require Kafka enable.auto.commit=false");
    }
  }
}
