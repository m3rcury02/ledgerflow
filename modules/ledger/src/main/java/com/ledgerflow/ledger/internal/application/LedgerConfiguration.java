package com.ledgerflow.ledger.internal.application;

import com.ledgerflow.ledger.api.LedgerPosting;
import com.ledgerflow.ledger.internal.persistence.JdbcLedgerStore;
import com.ledgerflow.payments.api.PaymentAccounting;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LedgerConfiguration {

  @Bean
  LedgerPosting ledgerPosting(JdbcLedgerStore ledgerStore, PaymentAccounting paymentAccounting) {
    return new LedgerPostingService(ledgerStore, paymentAccounting, Clock.systemUTC());
  }
}
