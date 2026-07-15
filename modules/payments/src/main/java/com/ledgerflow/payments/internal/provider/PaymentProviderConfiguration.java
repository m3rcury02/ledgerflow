package com.ledgerflow.payments.internal.provider;

import com.ledgerflow.operations.api.FaultInjection;
import com.ledgerflow.operations.api.WorkTracker;
import com.ledgerflow.payments.internal.application.PaymentMetrics;
import com.ledgerflow.payments.internal.application.PaymentProvider;
import com.ledgerflow.payments.internal.application.PaymentRetryPolicy;
import com.ledgerflow.payments.internal.application.PaymentStore;
import com.ledgerflow.payments.internal.application.PaymentWorkflowService;
import com.ledgerflow.payments.internal.application.ProviderRetryClassifier;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ledgerflow.payment.provider", name = "base-url")
@EnableConfigurationProperties(PaymentProviderProperties.class)
public class PaymentProviderConfiguration {

  @Bean(destroyMethod = "")
  HttpClient paymentProviderHttpClient(PaymentProviderProperties properties) {
    return HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
  }

  @Bean
  PaymentProviderHttpClientShutdown paymentProviderHttpClientShutdown(
      HttpClient paymentProviderHttpClient, PaymentProviderProperties properties) {
    return new PaymentProviderHttpClientShutdown(
        paymentProviderHttpClient, properties.overallTimeout());
  }

  @Bean
  @DependsOn("paymentProviderHttpClientShutdown")
  PaymentProvider paymentProvider(
      HttpClient paymentProviderHttpClient,
      ObjectMapper objectMapper,
      OpenTelemetry openTelemetry,
      PaymentProviderProperties properties,
      CircuitBreaker paymentProviderCircuitBreaker,
      Bulkhead paymentProviderBulkhead,
      PaymentProviderMetrics paymentProviderMetrics,
      WorkTracker workTracker,
      FaultInjection faultInjection) {
    PaymentProvider httpProvider =
        new HttpPaymentProviderAdapter(
            paymentProviderHttpClient,
            objectMapper,
            properties.baseUrl(),
            properties.readTimeout(),
            properties.overallTimeout(),
            openTelemetry);
    return new ResilientPaymentProvider(
        httpProvider,
        paymentProviderCircuitBreaker,
        paymentProviderBulkhead,
        workTracker,
        faultInjection,
        paymentProviderMetrics);
  }

  @Bean
  CircuitBreaker paymentProviderCircuitBreaker(PaymentProviderProperties properties) {
    CircuitBreakerConfig config =
        CircuitBreakerConfig.custom()
            .failureRateThreshold(
                100.0f
                    * properties.circuitFailureThreshold()
                    / properties.circuitSlidingWindowSize())
            .minimumNumberOfCalls(properties.circuitSlidingWindowSize())
            .slidingWindowSize(properties.circuitSlidingWindowSize())
            .waitDurationInOpenState(properties.circuitOpenDuration())
            .permittedNumberOfCallsInHalfOpenState(properties.circuitHalfOpenCalls())
            .recordResult(PaymentProviderConfiguration::providerAvailabilityFailure)
            .build();
    return CircuitBreaker.of("payment-provider", config);
  }

  @Bean
  Bulkhead paymentProviderBulkhead(PaymentProviderProperties properties) {
    return Bulkhead.of(
        "payment-provider",
        BulkheadConfig.custom()
            .maxConcurrentCalls(properties.maxConcurrentCalls())
            .maxWaitDuration(java.time.Duration.ZERO)
            .build());
  }

  @Bean
  PaymentProviderMetrics paymentProviderMetrics(
      MeterRegistry meterRegistry,
      CircuitBreaker paymentProviderCircuitBreaker,
      Bulkhead paymentProviderBulkhead) {
    return new PaymentProviderMetrics(
        meterRegistry, paymentProviderCircuitBreaker, paymentProviderBulkhead);
  }

  @Bean("paymentProviderCircuit")
  PaymentProviderCircuitHealthIndicator paymentProviderCircuitHealthIndicator(
      CircuitBreaker paymentProviderCircuitBreaker) {
    return new PaymentProviderCircuitHealthIndicator(paymentProviderCircuitBreaker);
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
  ProviderRetryClassifier providerRetryClassifier() {
    return new ProviderRetryClassifier();
  }

  @Bean
  PaymentWorkflowService paymentWorkflowService(
      PaymentStore paymentStore,
      PaymentProvider paymentProvider,
      PaymentRetryPolicy paymentRetryPolicy,
      ProviderRetryClassifier retryClassifier,
      PaymentMetrics paymentMetrics) {
    return new PaymentWorkflowService(
        paymentStore,
        paymentProvider,
        paymentRetryPolicy,
        retryClassifier,
        Clock.systemUTC(),
        UUID::randomUUID,
        paymentMetrics);
  }

  static boolean providerAvailabilityFailure(Object result) {
    return result
            instanceof com.ledgerflow.payments.internal.application.ProviderResult.TemporaryFailure
        || result instanceof com.ledgerflow.payments.internal.application.ProviderResult.Unknown
        || result
            instanceof com.ledgerflow.payments.internal.application.ProviderResult.InvalidResponse
        || result
            instanceof
            com.ledgerflow.payments.internal.application.ProviderLookupResult.TemporarilyUnavailable
        || result
            instanceof
            com.ledgerflow.payments.internal.application.ProviderLookupResult.InvalidResponse;
  }
}
