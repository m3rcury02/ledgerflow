# LedgerFlow Observability

- Status: Milestone 7A Complete
- Last updated: 2026-07-17
- Acceptance evidence: `mvp-evidence.md`

## Signal architecture

LedgerFlow emits three independent signals. Structured ECS JSON remains on standard output and is
also exported as bounded OpenTelemetry log records through the explicitly installed Logback
bridge. Traces and logs use bounded asynchronous OTLP queues and timeouts to the local Collector.
Prometheus scrapes Micrometer metrics directly from the isolated management listener; it does not
scrape the public application listener. Export failure can drop telemetry, but it cannot roll back
or change an order, payment, journal, outbox, inbox, or notification transaction.

The local path is:

```text
LedgerFlow OTLP traces -> Collector -> Tempo
LedgerFlow OTLP logs   -> Collector -> Loki
LedgerFlow /actuator/prometheus on host port 8082 -> Prometheus
Collector self-metrics -> Prometheus
Prometheus + Tempo + Loki -> provisioned Grafana data sources and dashboards
```

`infra/grafana/provisioning/datasources/datasources.yaml` links Prometheus exemplars to Tempo,
Tempo traces to Loki logs by trace ID, and Loki `trace_id` fields back to Tempo. The five dashboards
under `infra/grafana/dashboards` cover the API, provider/payment, ledger,
outbox/Kafka/notification, and JVM/dependencies. No UI edits are needed or authoritative.

The local Prometheus target `host.docker.internal:8082` follows the README's local management-port
convention because Keycloak occupies host port 8081. A different local management port requires a
matching Prometheus target change. Production must use service discovery and the management
network policy in `deployment-security.md`; it must never copy this host-gateway topology.

## Trace model

Inbound HTTP uses Spring's server observation and consumes only W3C `traceparent`/`tracestate`.
Malformed trace context is ignored and replaced by a new valid context. `X-Correlation-Id` is a
separate validated search key; an absent or malformed value is replaced and every HTTP response
returns the selected safe value.

The successful create flow adds bounded spans named:

1. `order.workflow`;
2. `db.order.initialize`;
3. `payment.provider.authorize` and `payment.provider.capture`;
4. `ledger.capture-accounting`;
5. `outbox.append`;
6. `db.order.finalize`;
7. `outbox.publish`; and
8. the Kafka consumer observation plus `notification.process`.

The JDK provider client injects the active W3C context. It records stage and a bounded outcome, not
the request URL, provider operation ID, body, token, response, or exception message. Database spans
describe the PostgreSQL workflow boundary and never include SQL parameters.

The outbox persists the valid originating `traceparent` and optional `tracestate`. The delayed
publisher restores that context and creates the producer span, so a normal outbox delay remains a
causally connected child rather than an unrelated trace. Kafka observations inject/extract W3C
headers and the notification persistence span is a child of consumer processing. These boundaries
retain a trustworthy causal parent. A later independent recovery command cannot safely pretend to
be an immediate child, so operator requests and retry-worker spans link to the stored valid origin
context instead of adopting false parentage.

The automated full-flow trace test uses an in-memory exporter and a fixed sampled trace ID. The
test verifies provider injection, persisted outbox context, connected producer/consumer work, and
the named business/database spans. `scripts/demo-observability` creates the same flow against a
running local application, prints the trace ID, and verifies that Tempo and Loki both return that
ID.

## Metrics and label policy

Spring Boot supplies bounded HTTP route-template metrics, JVM/process/thread metrics, HikariCP
pool metrics, Kafka client metrics, and Prometheus exemplars. LedgerFlow supplies these business
metrics:

| Metric | Type | Bounded labels | Meaning |
| --- | --- | --- | --- |
| `ledgerflow.orders.workflow` | Counter | `outcome` | Created, completed, declined, retry-pending, failed, replayed, conflict, or system-failure commands |
| `ledgerflow.payments.outcomes` | Counter | `stage`, `outcome` | Persisted payment-stage result |
| `ledgerflow.payments.state.transitions` | Counter | `state` | Committed payment transitions, including accounting/finalization states |
| `ledgerflow.payment.provider.attempts` | Counter | `stage`, `activity`, `outcome` | Actual provider call/lookup attempts |
| `ledgerflow.payment.provider.duration` | Histogram | `stage`, `activity`, `outcome` | Provider latency |
| `ledgerflow.payment.provider.timeouts` | Counter | None | Call timeout that produced an unknown outcome |
| `ledgerflow.payment.provider.circuit.state` | Gauge | `state` | One-hot closed/open/half-open state |
| `ledgerflow.payment.provider.circuit.transitions` | Counter | `state` | Circuit transitions |
| `ledgerflow.payment.provider.circuit.rejections` | Counter | None | Open-circuit rejections |
| `ledgerflow.payment.provider.bulkhead.available` / `.maximum` | Gauge | None | Concurrency permits |
| `ledgerflow.payment.provider.bulkhead.rejections` | Counter | None | Zero-queue bulkhead rejections |
| `ledgerflow.ledger.postings` | Counter | `outcome` | New, replayed, conflicting, or failed capture journals |
| `ledgerflow.outbox.appends` | Counter | `outcome` | Committed new/duplicate logical events or conflicting/failed append attempts |
| `ledgerflow.outbox.records` | Gauge | `state` | Due, leased, and failed records from a cached bounded query |
| `ledgerflow.outbox.oldest.age` | Gauge | None | Oldest unpublished age in seconds |
| `ledgerflow.outbox.publications` | Counter | `outcome` | Published, retried, exhausted, invalid, or stale-owner attempts |
| `ledgerflow.outbox.publication.delay` | Histogram | None | Business occurrence to broker acknowledgement |
| `ledgerflow.kafka.consumer.records` | Counter | `outcome` | Processed, retry, DLT, or failed consumer handling |
| `ledgerflow.notifications.effects` | Counter | `outcome` | Created, transport duplicate/conflict, semantic duplicate/conflict |
| `ledgerflow.notifications.processing.delay` | Histogram | `outcome` | Capture occurrence to notification persistence/deduplication |
| `ledgerflow.notifications.dlt.terminal` | Counter | `outcome` | Terminal malformed-DLT evidence results |
| `ledgerflow.readiness.checks` / `.status` | Counter/Gauge | `outcome` on checks | Uncached probe outcomes and last result |
| `ledgerflow.graceful.drain.active` / `.accepting` | Gauge | None | Drain admission and in-flight work |
| `ledgerflow.graceful.drain.results` | Counter | `outcome` | Completed, timed-out, or interrupted drains |
| `ledgerflow.executor.active` / `.pool.size` | Gauge | `executor` | Bounded outbox and notification-retry executors |
| `ledgerflow.operator.commands` | Gauge | `state` | Pending, in-progress, waiting, and failed retry commands |
| `ledgerflow.operator.oldest.active.age.seconds` | Gauge | None | Age of the oldest active retry command |
| `ledgerflow.operator.retries` | Counter | `operation`, `outcome` | Bounded retry-command acceptance and execution outcomes |
| `ledgerflow.operator.lease.takeovers` | Counter | `operation` | Expired retry leases safely claimed by another worker |
| `ledgerflow.operator.breakglass` | Counter | `outcome` | Break-glass approval and use outcomes |

Prometheus converts Micrometer dots to underscores, adds `_total` to counters, and adds `_seconds`
to timers and second-based gauges where applicable. Alert and dashboard expressions use those
exported Prometheus names.

For every `ledgerflow.*` meter, the only permitted label keys are `stage`, `activity`, `state`,
`executor`, `operation`, and `outcome`. Payment lifecycle states, outbox/circuit/recovery states,
operation types, executor names, and all result values are explicitly declared in the code
allowlist. A registry filter rejects a
new label key/value at registration and a regression test inspects the complete meter set. HTTP
metrics use normalized route templates rather than raw URLs. No metric label may contain a
customer subject, correlation ID, order/payment/event/provider ID, idempotency key, Kafka
coordinate, hash, exception text, URL with an identifier, token, or personal value.

## SLIs and provisional SLOs

These are initial 28-day objectives for design and alert exercises. They require production load,
traffic mix, capacity, and error-budget review before adoption. Measurements from a laptop Compose
environment or integration tests are demonstration evidence, not production guarantees.

| SLI | Provisional objective | Measurement and exclusions |
| --- | --- | --- |
| API availability | 99.5% | Non-5xx order responses divided by all authenticated order responses; 4xx client rejection and confirmed business decline are not system failure |
| Order-creation latency | 95% under 2 s | POST route-template histogram for all completed HTTP results; report provider business-decline latency separately |
| Successful workflow completion | 99.0% | `completed` divided by completed plus system failure/retry-pending/unknown; confirmed declines form a separate business-outcome series |
| Provider outcome reconciliation | 99.0% within 5 min | Unknown provider outcomes followed by persisted success/decline/not-found resolution; do not count a confirmed decline as failure |
| Outbox publication delay | 99.0% under 60 s | Publication-delay histogram plus oldest-unpublished gauge; failed rows remain visible rather than removed from the denominator |
| Notification processing delay | 99.0% under 60 s | Capture occurrence to applied or idempotently suppressed notification processing |
| Ledger uniqueness | 100% | No `conflict` or `failure` posting and exactly one immutable capture journal per payment; safe `replay` is not a duplicate financial effect |
| Notification semantic uniqueness | 100% | No semantic conflict and one notification row per versioned effect identity; semantic duplicate means the duplicate was suppressed |

Availability and workflow panels must show confirmed provider declines separately from temporary,
unknown, invalid, and system outcomes. A decline is a valid business decision, not an availability
failure.

## Logging and data minimization

Production console logs are ECS JSON. Business logs use stable `event_code`, `action`, `outcome`,
and `error_code` fields. OpenTelemetry supplies `trace.id`/`span.id`; the correlation filter adds
only the validated `correlation_id`. Messages are fixed summaries and do not concatenate untrusted
input.

The OTLP Logback appender exports INFO-and-above even if local console diagnostics temporarily
enable DEBUG. It captures only SLF4J key/value fields controlled by LedgerFlow and the explicit
`correlation_id` MDC field; it does not capture arbitrary MDC, logger-context, message-template,
argument, marker, code, thread, or baggage attributes. The pre-SDK startup buffer is capped at 100
records. This keeps framework DEBUG SQL and response representations out of Loki while preserving
local console troubleshooting.

Logs, trace attributes/events, metrics, and OTLP headers must exclude bearer/security tokens,
authorization headers, cookies, idempotency keys, payment-method references, PAN-like values,
CVVs, provider credentials, raw provider/Kafka bodies, poison payloads, SQL parameters, customer
subjects, and unnecessary personal data. Exception messages and stacks are not placed in custom
spans. Seeded-marker integration tests inspect responses, console logs, exported OpenTelemetry log
records, spans, metric IDs, and event headers.

## Validation and local evidence

Run:

```bash
./gradlew observabilityValidate
./gradlew :application:integrationTest --tests '*ObservabilityIntegrationTest'
./gradlew :application:integrationTest --tests '*TracePropagationIntegrationTest'
./gradlew :application:integrationTest --tests '*SensitiveTelemetryIntegrationTest'
scripts/demo-observability
```

The first command validates Compose resolution, Collector configuration, every Prometheus rule,
Grafana provisioning, and every dashboard JSON file. The demonstration requires a running app,
the local stack, enabled publisher/consumers, a separately running implementation of the test-only
mock-provider contract, and a valid customer token in `LEDGERFLOW_ACCESS_TOKEN`. The script never
prints that token. It fails telemetry verification independently after reporting whether the
business transaction completed.

The in-process release proof is `TracePropagationIntegrationTest`; it deterministically verifies
the full HTTP/provider/database/ledger/outbox/Kafka/notification trace without requiring a shared
telemetry backend. `scripts/demo-observability` is the separate local Tempo/Loki walkthrough. The
two forms of evidence are mapped without conflation in [MVP evidence](mvp-evidence.md).
