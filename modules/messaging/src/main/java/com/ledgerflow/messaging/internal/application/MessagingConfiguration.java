package com.ledgerflow.messaging.internal.application;

import com.ledgerflow.messaging.api.EventEnvelopeCodec;
import com.ledgerflow.messaging.api.OutboxEventAppender;
import com.ledgerflow.messaging.internal.kafka.KafkaTracePropagation;
import com.ledgerflow.messaging.internal.persistence.JdbcOutboxStore;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingConfiguration {

  @Bean
  EventEnvelopeCodec eventEnvelopeCodec(ObjectMapper objectMapper) {
    return new EventEnvelopeCodec(objectMapper);
  }

  @Bean
  OutboxEventAppender outboxEventAppender(
      JdbcOutboxStore outboxStore,
      EventEnvelopeCodec codec,
      MessagingProperties messagingProperties) {
    return new PaymentCapturedOutboxAppender(outboxStore, codec, messagingProperties);
  }

  @Bean
  @ConditionalOnProperty(name = "ledgerflow.messaging.publisher-enabled", havingValue = "true")
  OutboxPublisher outboxPublisher(
      JdbcOutboxStore outboxStore,
      KafkaTemplate<String, String> kafkaTemplate,
      MessagingProperties messagingProperties,
      EventEnvelopeCodec eventEnvelopeCodec,
      ObjectProvider<OpenTelemetry> openTelemetryProvider,
      ObjectProvider<OutboxAcknowledgementHook> hookProvider) {
    OpenTelemetry openTelemetry = openTelemetryProvider.getIfAvailable(GlobalOpenTelemetry::get);
    OutboxAcknowledgementHook hook =
        hookProvider.getIfAvailable(() -> eventId -> java.util.Objects.requireNonNull(eventId));
    return new OutboxPublisher(
        outboxStore,
        kafkaTemplate,
        messagingProperties,
        eventEnvelopeCodec,
        new KafkaTracePropagation(openTelemetry),
        hook,
        Clock.systemUTC());
  }

  @Bean
  @ConditionalOnProperty(name = "ledgerflow.messaging.publisher-enabled", havingValue = "true")
  TaskScheduler outboxTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("outbox-publisher-");
    return scheduler;
  }
}
