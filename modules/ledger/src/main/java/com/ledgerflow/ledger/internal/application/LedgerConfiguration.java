package com.ledgerflow.ledger.internal.application;

import com.ledgerflow.ledger.api.LedgerPosting;
import com.ledgerflow.ledger.internal.persistence.JdbcLedgerStore;
import com.ledgerflow.messaging.api.OutboxEventAppender;
import com.ledgerflow.payments.api.PaymentAccounting;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LedgerConfiguration {

  @Bean
  LedgerPostingService ledgerPostingService(
      JdbcLedgerStore ledgerStore,
      PaymentAccounting paymentAccounting,
      OutboxEventAppender outboxEventAppender) {
    return new LedgerPostingService(
        ledgerStore, paymentAccounting, outboxEventAppender, Clock.systemUTC());
  }

  @Bean
  LedgerPosting ledgerPosting(
      LedgerPostingService service, MeterRegistry meterRegistry, OpenTelemetry openTelemetry) {
    return new ObservedLedgerPosting(
        service, new LedgerObservability(meterRegistry, openTelemetry));
  }
}
