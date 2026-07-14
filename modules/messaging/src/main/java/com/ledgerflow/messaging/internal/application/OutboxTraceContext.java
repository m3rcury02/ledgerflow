package com.ledgerflow.messaging.internal.application;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.util.stream.Collectors;

public record OutboxTraceContext(String traceparent, String tracestate) {

  public static OutboxTraceContext capture() {
    SpanContext context = Span.current().getSpanContext();
    if (!context.isValid()) {
      return new OutboxTraceContext(null, null);
    }
    String traceparent =
        "00-"
            + context.getTraceId()
            + "-"
            + context.getSpanId()
            + "-"
            + context.getTraceFlags().asHex();
    String tracestate =
        context.getTraceState().isEmpty()
            ? null
            : context.getTraceState().asMap().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(","));
    return new OutboxTraceContext(traceparent, tracestate);
  }
}
