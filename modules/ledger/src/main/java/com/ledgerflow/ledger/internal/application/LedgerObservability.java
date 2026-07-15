package com.ledgerflow.ledger.internal.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.util.EnumMap;
import java.util.Map;

final class LedgerObservability {

  private final OpenTelemetry openTelemetry;
  private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);

  LedgerObservability(MeterRegistry meterRegistry, OpenTelemetry openTelemetry) {
    this.openTelemetry = openTelemetry;
    for (Outcome outcome : Outcome.values()) {
      counters.put(
          outcome,
          Counter.builder("ledgerflow.ledger.postings")
              .description("Capture-ledger posting results")
              .tag("outcome", outcome.tag)
              .register(meterRegistry));
    }
  }

  PostingSpan startCapturePosting() {
    Span span =
        openTelemetry
            .getTracer("com.ledgerflow.ledger")
            .spanBuilder("ledger.capture-accounting")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("db.system.name", "postgresql")
            .startSpan();
    return new PostingSpan(span, span.makeCurrent());
  }

  void record(Outcome outcome) {
    counters.get(outcome).increment();
  }

  enum Outcome {
    SUCCESS("success"),
    REPLAY("replay"),
    CONFLICT("conflict"),
    FAILURE("failure");

    private final String tag;

    Outcome(String tag) {
      this.tag = tag;
    }
  }

  static final class PostingSpan implements AutoCloseable {

    private final Span span;
    private final Scope scope;

    private PostingSpan(Span span, Scope scope) {
      this.span = span;
      this.scope = scope;
    }

    void outcome(Outcome outcome) {
      span.setAttribute("ledgerflow.outcome", outcome.tag);
      if (outcome == Outcome.CONFLICT || outcome == Outcome.FAILURE) {
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
