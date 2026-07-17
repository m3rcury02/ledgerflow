package com.ledgerflow.operations.internal.recovery;

import com.ledgerflow.operations.api.OperationRecoveryContext;
import com.ledgerflow.operations.api.OperationRecoveryHandler;
import com.ledgerflow.operations.api.OperationRecoveryResult;
import com.ledgerflow.operations.api.OperationType;
import com.ledgerflow.operations.api.WorkToken;
import com.ledgerflow.operations.api.WorkTracker;
import com.ledgerflow.operations.internal.OperationsProperties;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

final class OperatorRetryWorker {

  private static final Logger LOGGER = LoggerFactory.getLogger(OperatorRetryWorker.class);

  private final OperatorRecoveryStore store;
  private final Map<OperationType, OperationRecoveryHandler> handlers;
  private final OperationsProperties properties;
  private final OperatorRecoveryMetrics metrics;
  private final WorkTracker workTracker;
  private final Clock clock;
  private final Tracer tracer;
  private final String workerId = UUID.randomUUID().toString();

  OperatorRetryWorker(
      OperatorRecoveryStore store,
      List<OperationRecoveryHandler> handlers,
      OperationsProperties properties,
      OperatorRecoveryMetrics metrics,
      WorkTracker workTracker,
      Clock clock,
      OpenTelemetry openTelemetry) {
    this.store = store;
    this.handlers = index(handlers);
    this.properties = properties;
    this.metrics = metrics;
    this.workTracker = workTracker;
    this.clock = clock;
    this.tracer = openTelemetry.getTracer("com.ledgerflow.operations.recovery");
  }

  @Scheduled(
      fixedDelayString = "${ledgerflow.operations.recovery-poll-interval:1s}",
      initialDelayString = "${ledgerflow.operations.recovery-poll-interval:1s}")
  void processPendingCommands() {
    if (!properties.recoveryWorkerEnabled() || !workTracker.isAcceptingWork()) {
      return;
    }
    WorkToken work = workTracker.begin("operator-recovery-batch");
    try {
      for (int handled = 0; handled < properties.recoveryBatchSize(); handled++) {
        List<RetryClaim> claims =
            store.claimBatch(workerId, 1, clock.instant(), properties.recoveryLeaseDuration());
        if (claims.isEmpty()) {
          break;
        }
        process(claims.getFirst());
      }
    } finally {
      metrics.refresh(store.snapshot(clock.instant()));
      work.close();
    }
  }

  private void process(RetryClaim claim) {
    RetryCommand command = claim.command();
    if (claim.takeover()) {
      metrics.takeover(command.operationType());
    }
    Span span = recoverySpan(command);
    Scope scope = span.makeCurrent();
    try {
      Instant now = clock.instant();
      if (!store.leaseIsCurrent(command, now)) {
        stale(command, now);
        return;
      }
      OperationRecoveryHandler handler = handlers.get(command.operationType());
      if (handler == null) {
        finish(command, OperationRecoveryResult.failed("HANDLER_UNAVAILABLE"));
        return;
      }
      OperationRecoveryContext context =
          new OperationRecoveryContext(
              command.id(),
              command.sourceId(),
              command.correlationId(),
              claim.firstExecution(),
              claim.takeover(),
              properties.recoveryRecheckDelay(),
              () -> {
                if (!store.leaseIsCurrent(command, clock.instant())) {
                  throw new StaleRecoveryLeaseException();
                }
              });
      OperationRecoveryResult result = handler.recover(context);
      finish(command, result);
    } catch (StaleRecoveryLeaseException exception) {
      stale(command, clock.instant());
      span.setAttribute("ledgerflow.recovery.outcome", "stale_owner");
    } catch (RuntimeException exception) {
      span.setAttribute("error.type", "recovery_handler_failure");
      finish(command, OperationRecoveryResult.failed("RECOVERY_HANDLER_FAILED"));
      LOGGER
          .atWarn()
          .addKeyValue("event_code", "OPERATOR_RECOVERY_HANDLER_FAILED")
          .addKeyValue("operation_type", command.operationType().name())
          .log("Operator recovery handler failed");
    } finally {
      scope.close();
      span.end();
    }
  }

  private void finish(RetryCommand command, OperationRecoveryResult result) {
    Instant now = clock.instant();
    boolean updated =
        switch (result.status()) {
          case WAITING ->
              store.markWaiting(command, result.code(), now.plus(result.recheckAfter()), now);
          case COMPLETED -> store.markCompleted(command, result.code(), now);
          case FAILED ->
              store.markFailed(command, result.code(), now.plus(properties.retryCooldown()), now);
        };
    String outcome =
        updated ? result.status().name().toLowerCase(java.util.Locale.ROOT) : "stale_owner";
    metrics.retry(command.operationType(), outcome);
    Span.current().setAttribute("ledgerflow.recovery.outcome", outcome);
    if (!updated) {
      stale(command, now);
    }
  }

  private void stale(RetryCommand command, Instant now) {
    store.recordStaleCompletion(command, now);
    metrics.retry(command.operationType(), "stale_owner");
  }

  private Span recoverySpan(RetryCommand command) {
    var builder =
        tracer
            .spanBuilder("operator.recovery.execute")
            .setSpanKind(SpanKind.INTERNAL)
            .setNoParent()
            .setAttribute(
                "ledgerflow.operation.type",
                command.operationType().name().toLowerCase(java.util.Locale.ROOT))
            .setAttribute(
                "ledgerflow.recovery.attempt_kind",
                command.attemptKind().toLowerCase(java.util.Locale.ROOT));
    addLink(builder, command.requestTraceparent());
    addLink(builder, command.originTraceparent());
    return builder.startSpan();
  }

  private void addLink(io.opentelemetry.api.trace.SpanBuilder builder, String traceparent) {
    SpanContext context = parseTraceparent(traceparent);
    if (context.isValid()) {
      builder.addLink(context);
    }
  }

  private SpanContext parseTraceparent(String value) {
    if (value == null || !value.matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$")) {
      return SpanContext.getInvalid();
    }
    try {
      return SpanContext.createFromRemoteParent(
          value.substring(3, 35),
          value.substring(36, 52),
          TraceFlags.fromHex(value, 53),
          TraceState.getDefault());
    } catch (IllegalArgumentException exception) {
      return SpanContext.getInvalid();
    }
  }

  private Map<OperationType, OperationRecoveryHandler> index(
      List<OperationRecoveryHandler> availableHandlers) {
    Map<OperationType, OperationRecoveryHandler> result = new EnumMap<>(OperationType.class);
    for (OperationRecoveryHandler handler : availableHandlers) {
      if (result.putIfAbsent(handler.operationType(), handler) != null) {
        throw new IllegalStateException("multiple recovery handlers configured for one type");
      }
    }
    return Map.copyOf(result);
  }
}
