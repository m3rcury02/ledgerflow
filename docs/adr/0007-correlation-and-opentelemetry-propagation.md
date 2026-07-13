# ADR 0007: Propagate Correlation and OpenTelemetry Context End to End

- Status: Proposed
- Date: 2026-07-11
- Decision owners: LedgerFlow maintainers

## Context

The MVP crosses inbound HTTP, provider HTTP, delayed outbox publication, Kafka retries/DLT, consumer processing, and operator recovery. Thread-local logging alone cannot connect these operations, and an outbox delay breaks naive in-memory trace propagation.

Correlation identifiers and distributed trace context solve related but different problems. Neither is an authorization credential.

## Decision

Use W3C `traceparent` and `tracestate` through OpenTelemetry for distributed tracing. Use a separate `X-Correlation-Id` for operator-facing search and structured logs.

At HTTP ingress:

- accept a valid 1–64 character correlation ID matching `[A-Za-z0-9._-]+` or generate a UUID replacement;
- return the effective value on every response;
- extract valid W3C trace context or start a new trace; and
- do not accept arbitrary baggage in the MVP.

Outbound provider HTTP injects trace context and the effective correlation ID.

The outbox row persists correlation ID plus the approved trace context captured when the event is created. The delayed publisher restores that context, creates a producer span, and injects its context into Kafka headers. Consumers extract the Kafka context before creating process spans.

Automatic retry attempts create distinct spans while retaining event correlation and causation. Failed-operation evidence stores validated originating trace context. An operator retry starts a new trace/correlation and creates span links to both the operator request and failed operation/event rather than pretending it is an immediate child.

For DLT replay, the immutable event envelope keeps its original business correlation. Kafka headers use the new retry correlation and newly injected producer trace; old delivery/retry/exception headers are removed. The retry request ID provides explicit causation.

Structured logs use stable fields such as `correlation_id`, `trace_id`, `span_id`, `operation`, `order_id`, `payment_id`, `event_id`, `attempt`, `outcome`, and bounded `error_code`.

APIs, Kafka/DLT headers, operator projections, and span attributes/events must not contain bearer tokens, payment references, idempotency keys, request/response bodies, raw provider responses, operator reasons, secrets, or stack traces. Protected structured server error logs may contain redacted internal stack traces under restricted access and retention. IDs are not metric labels. Telemetry export is asynchronous and failure cannot fail business processing.

## Consequences

### Positive

- Operators can follow one flow across synchronous and asynchronous boundaries.
- Outbox delay does not erase trace causality.
- Manual recovery remains linked without creating misleading parent/child timing.
- Explicit allowlists reduce telemetry data leakage and cardinality risk.

### Costs and risks

- Trace context becomes persisted operational metadata with retention implications.
- Instrumentation and redaction require integration tests.
- Sampling may omit some successful traces.
- Correlation and tracing libraries add runtime configuration and exporter failure modes.

## Alternatives considered

### Use correlation ID as the trace ID

Rejected because trace identifiers have protocol semantics and should be managed by OpenTelemetry.

### Start a new unrelated trace at Kafka consumption

Rejected because it prevents end-to-end causality analysis.

### Put correlation ID in unrestricted W3C baggage

Rejected to avoid propagating untrusted arbitrary baggage and accidental attribute expansion.

### Fail business work when telemetry export fails

Rejected because observability is not part of the financial transaction.

## References

- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
- [OpenTelemetry context propagation](https://opentelemetry.io/docs/concepts/context-propagation/)
- [OpenTelemetry messaging span conventions](https://opentelemetry.io/docs/specs/semconv/messaging/messaging-spans/)
