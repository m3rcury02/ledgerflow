# ADR 0007: Propagate Correlation and OpenTelemetry Context Through Kafka

- Status: Accepted for implemented Kafka propagation; operator linking remains proposed
- Date: 2026-07-11
- Accepted: 2026-07-13
- Decision owners: LedgerFlow maintainers

## Context

Inbound HTTP, provider HTTP, delayed outbox publication, Kafka consumption, DLT handling, and replay do not share a thread or lifetime. A business correlation identifier is useful for operator search, while W3C trace context provides distributed-tracing semantics. Neither is an authorization credential.

The repository already validates and propagates HTTP correlation IDs. This ADR accepts the implemented Kafka propagation only. A secured operator HTTP recovery workflow and trace links from an operator request remain future work.

## Decision

The outbox row stores the validated business correlation ID plus the originating W3C `traceparent` and optional `tracestate`. The delayed publisher restores that origin where available, creates an OpenTelemetry producer span, and injects current W3C trace headers into the Kafka record. The Kafka correlation header is `x-correlation-id`; identity headers include `event_id`, `event_type`, and `schema_version`.

The notification and DLT listeners extract Kafka W3C headers before processing. Invalid or absent context cannot become trusted application state. The event envelope retains the original business correlation independently of delivery headers.

DLT replay preserves the canonical envelope and Kafka key but removes prior exception, DLT-routing, and delivery metadata. It creates a new replay request ID, transport correlation ID, and independent producer trace. Thus the replay is observable as a later operation rather than a misleading immediate child of the original send. Linking a future operator HTTP request to stored origin context remains proposed.

Structured logs use bounded fields such as `correlation_id`, `trace_id`, `span_id`, `operation`, `payment_id`, `event_id`, `attempt`, `outcome`, and stable error code. Kafka headers, spans, logs, DLT rows, and replay audit must not contain bearer tokens, payment-method references, idempotency keys, raw payloads/provider responses, secrets, or stack traces. IDs are not metric labels, and telemetry export failure cannot roll back business processing.

## Consequences

Outbox delay and replay preserve observable causality without conflating business and trace identifiers. The costs are persisted operational metadata, explicit header allowlists, retention requirements, and tests for propagation/redaction. Sampling may still omit successful traces.

Operator-request correlation, authorization, failure inspection, and span-link policy will be accepted only with the future operator HTTP milestone.

## Alternatives considered

### Use correlation ID as the trace ID

Rejected because trace IDs have W3C/OpenTelemetry protocol semantics.

### Start unrelated Kafka traces

Rejected because it discards useful causality across the outbox delay.

### Preserve old transport headers during replay

Rejected because stale delivery/exception metadata is misleading and may leak implementation detail.

### Fail business work when telemetry export fails

Rejected because observability is not part of the financial transaction.

## References

- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
- [OpenTelemetry context propagation](https://opentelemetry.io/docs/concepts/context-propagation/)
- [OpenTelemetry messaging span conventions](https://opentelemetry.io/docs/specs/semconv/messaging/messaging-spans/)
