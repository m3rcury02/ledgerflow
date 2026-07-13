package com.ledgerflow.payments.internal.provider;

import com.ledgerflow.payments.internal.application.PaymentProvider;
import com.ledgerflow.payments.internal.application.PaymentRetryPolicy;
import com.ledgerflow.payments.internal.application.PaymentStore;
import com.ledgerflow.payments.internal.application.PaymentWorkflowService;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ledgerflow.payment.provider", name = "base-url")
@EnableConfigurationProperties(PaymentProviderProperties.class)
public class PaymentProviderConfiguration {

  @Bean
  HttpClient paymentProviderHttpClient(PaymentProviderProperties properties) {
    return HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
  }

  @Bean
  PaymentProvider paymentProvider(
      HttpClient paymentProviderHttpClient,
      ObjectMapper objectMapper,
      PaymentProviderProperties properties) {
    return new HttpPaymentProviderAdapter(
        paymentProviderHttpClient, objectMapper, properties.baseUrl(), properties.requestTimeout());
  }

  @Bean
  PaymentRetryPolicy paymentRetryPolicy(PaymentProviderProperties properties) {
    return new PaymentRetryPolicy(
        properties.maxAttempts(),
        properties.baseBackoff(),
        properties.maxBackoff(),
        properties.backoffMultiplier(),
        properties.jitterRatio(),
        () -> ThreadLocalRandom.current().nextDouble(),
        Thread::sleep);
  }

  @Bean
  PaymentWorkflowService paymentWorkflowService(
      PaymentStore paymentStore,
      PaymentProvider paymentProvider,
      PaymentRetryPolicy paymentRetryPolicy) {
    return new PaymentWorkflowService(
        paymentStore, paymentProvider, paymentRetryPolicy, Clock.systemUTC(), UUID::randomUUID);
  }
}
