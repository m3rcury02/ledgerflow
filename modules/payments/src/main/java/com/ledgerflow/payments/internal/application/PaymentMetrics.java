package com.ledgerflow.payments.internal.application;

import com.ledgerflow.payments.internal.domain.PaymentStage;
import com.ledgerflow.payments.internal.domain.PaymentState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public final class PaymentMetrics {

  private final Map<PaymentStage, Map<Outcome, Counter>> counters =
      new EnumMap<>(PaymentStage.class);
  private final Map<PaymentState, Counter> stateTransitions = new EnumMap<>(PaymentState.class);

  public PaymentMetrics(MeterRegistry meterRegistry) {
    for (PaymentStage stage : PaymentStage.values()) {
      Map<Outcome, Counter> stageCounters = new EnumMap<>(Outcome.class);
      for (Outcome outcome : Outcome.values()) {
        stageCounters.put(
            outcome,
            Counter.builder("ledgerflow.payments.outcomes")
                .description("Persisted payment stage outcomes")
                .tag("stage", stage.name().toLowerCase(java.util.Locale.ROOT))
                .tag("outcome", outcome.tag)
                .register(meterRegistry));
      }
      counters.put(stage, stageCounters);
    }
    for (PaymentState state : PaymentState.values()) {
      stateTransitions.put(
          state,
          Counter.builder("ledgerflow.payments.state.transitions")
              .description("Committed payment transitions by bounded target state")
              .tag("state", state.name().toLowerCase(java.util.Locale.ROOT))
              .register(meterRegistry));
    }
  }

  public void record(PaymentStage stage, Outcome outcome) {
    counters.get(stage).get(outcome).increment();
  }

  public void recordState(PaymentState state) {
    stateTransitions.get(state).increment();
  }

  public void recordStateAfterCommit(PaymentState state) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      recordState(state);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status == TransactionSynchronization.STATUS_COMMITTED) {
              recordState(state);
            }
          }
        });
  }

  public enum Outcome {
    SUCCESS("success"),
    DECLINE("decline"),
    RETRY_PENDING("retry_pending"),
    UNKNOWN("unknown"),
    INVALID("invalid"),
    NOT_FOUND("not_found");

    private final String tag;

    Outcome(String tag) {
      this.tag = tag;
    }
  }
}
