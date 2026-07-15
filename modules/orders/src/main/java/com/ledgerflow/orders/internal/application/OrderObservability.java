package com.ledgerflow.orders.internal.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
final class OrderObservability {

  private final OpenTelemetry openTelemetry;
  private final Map<Outcome, Counter> outcomes = new EnumMap<>(Outcome.class);

  OrderObservability(MeterRegistry meterRegistry, OpenTelemetry openTelemetry) {
    this.openTelemetry = openTelemetry;
    for (Outcome outcome : Outcome.values()) {
      outcomes.put(
          outcome,
          Counter.builder("ledgerflow.orders.workflow")
              .description("Order workflow results by bounded outcome")
              .tag("outcome", outcome.tag)
              .register(meterRegistry));
    }
  }

  WorkflowSpan startWorkflow() {
    Span span =
        openTelemetry
            .getTracer("com.ledgerflow.orders")
            .spanBuilder("order.workflow")
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan();
    return new WorkflowSpan(span, span.makeCurrent());
  }

  <T> T inDatabaseSpan(String name, Supplier<T> operation) {
    Span span =
        openTelemetry
            .getTracer("com.ledgerflow.orders")
            .spanBuilder(name)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("db.system.name", "postgresql")
            .startSpan();
    Scope scope = span.makeCurrent();
    try {
      return operation.get();
    } catch (RuntimeException exception) {
      span.setStatus(StatusCode.ERROR, "database_operation_failed");
      throw exception;
    } finally {
      scope.close();
      span.end();
    }
  }

  void record(Outcome outcome) {
    outcomes.get(outcome).increment();
  }

  void recordAfterCommit(Outcome outcome) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      record(outcome);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status == TransactionSynchronization.STATUS_COMMITTED) {
              record(outcome);
            }
          }
        });
  }

  enum Outcome {
    CREATED("created"),
    COMPLETED("completed"),
    DECLINED("declined"),
    RETRY_PENDING("retry_pending"),
    FAILED("failed"),
    REPLAYED("replayed"),
    IDEMPOTENCY_CONFLICT("idempotency_conflict"),
    SYSTEM_FAILURE("system_failure");

    private final String tag;

    Outcome(String tag) {
      this.tag = tag;
    }
  }

  static final class WorkflowSpan implements AutoCloseable {

    private final Span span;
    private final Scope scope;

    private WorkflowSpan(Span span, Scope scope) {
      this.span = span;
      this.scope = scope;
    }

    void outcome(Outcome outcome) {
      span.setAttribute("ledgerflow.outcome", outcome.tag);
      if (outcome == Outcome.FAILED
          || outcome == Outcome.IDEMPOTENCY_CONFLICT
          || outcome == Outcome.SYSTEM_FAILURE) {
        span.setStatus(StatusCode.ERROR, outcome.tag);
      }
    }

    @Override
    public void close() {
      scope.close();
      span.end();
    }
  }
}
