package com.ledgerflow.messaging.internal.kafka;

import com.ledgerflow.messaging.internal.persistence.OutboxRecord;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.common.header.Headers;

public final class KafkaTracePropagation {

  private static final TextMapGetter<Map<String, String>> MAP_GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
          return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
          return carrier.get(key);
        }
      };

  private static final TextMapSetter<Headers> KAFKA_SETTER =
      (carrier, key, value) -> {
        if (carrier != null) {
          carrier.remove(key);
          carrier.add(key, value.getBytes(StandardCharsets.UTF_8));
        }
      };

  private final OpenTelemetry openTelemetry;

  public KafkaTracePropagation(OpenTelemetry openTelemetry) {
    this.openTelemetry = openTelemetry;
  }

  public PublishSpan start(OutboxRecord record, Headers headers) {
    Map<String, String> persisted = new HashMap<>();
    if (record.traceparent() != null) {
      persisted.put("traceparent", record.traceparent());
    }
    if (record.tracestate() != null) {
      persisted.put("tracestate", record.tracestate());
    }
    Context parent =
        W3CTraceContextPropagator.getInstance().extract(Context.root(), persisted, MAP_GETTER);
    Span span =
        openTelemetry
            .getTracer("com.ledgerflow.messaging")
            .spanBuilder("outbox publish " + record.eventType())
            .setSpanKind(SpanKind.PRODUCER)
            .setParent(parent)
            .startSpan();
    Context context = parent.with(span);
    W3CTraceContextPropagator.getInstance().inject(context, headers, KAFKA_SETTER);
    return new PublishSpan(span, context.makeCurrent());
  }

  public static final class PublishSpan implements AutoCloseable {

    private final Span span;
    private final Scope scope;

    private PublishSpan(Span span, Scope scope) {
      this.span = span;
      this.scope = scope;
    }

    public void failed(Throwable failure) {
      span.recordException(failure);
    }

    @Override
    public void close() {
      scope.close();
      span.end();
    }
  }
}
