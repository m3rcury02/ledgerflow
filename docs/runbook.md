# Payment, Ledger, and Messaging Recovery Runbook

- Status: Milestone 5B development runbook
- Last updated: 2026-07-13

## Scope and current limitation

This runbook covers authorization/capture failures, crash recovery, non-public capture accounting, outbox publication, notification consumption, DLT inspection, and narrow audited replay. The current slices intentionally expose no public payment or operator endpoint. Final order/payment workflow states and the secured general operations API remain later work.

In a deployed environment, support staff may perform the read-only inspection below and escalate with the payment ID and correlation ID. They must not invoke the test fixture, update payment rows, delete attempt history, or resend a provider request with a new request ID.

## State interpretation

| State | Meaning | Safe action |
| --- | --- | --- |
| `AUTHORIZING`, `CAPTURING` | A call started; the process may have stopped before recording the result | Reconcile by provider lookup using the persisted stage request ID |
| `AUTHORIZATION_UNKNOWN`, `CAPTURE_UNKNOWN` | The response path failed, so provider outcome is not known | Lookup first; never blind-retry |
| `AUTHORIZATION_RETRY_PENDING`, `CAPTURE_RETRY_PENDING` | Confirmed temporary failures exhausted the one automatic retry | Wait for an approved explicit retry using the same request ID |
| `DECLINED`, `CAPTURE_DECLINED` | Provider confirmed a business decline | Terminal; never retry automatically or manually as the same operation |
| `AUTHORIZED` | Authorization is confirmed | Capture may start once, with its independent persisted request ID |
| `CAPTURE_CONFIRMED` | Provider capture is confirmed but no capture journal committed | Invoke the approved internal ledger posting use case; do not resend provider capture |
| `CAPTURE_ACCOUNTED` | Exactly one matching balanced capture journal and payment-captured outbox event committed with this state | Treat capture accounting as complete; a repeated internal posting verifies and returns the original journal/event |
| `FAILED` | Provider response was invalid or contradictory | Investigate configuration/provider contract; do not retry automatically |

## Read-only inspection

Use a read-only database role and substitute a validated payment UUID. Do not place payment-method references, credentials, or provider response bodies into tickets or chat.

```sql
SELECT id, order_id, state, resume_stage,
       authorization_request_id, capture_request_id,
       provider_authorization_id, provider_capture_id,
       failure_code, authorization_attempt_count, capture_attempt_count,
       version, created_at, updated_at
FROM payments
WHERE id = '<payment-id>';
```

```sql
SELECT stage, activity, attempt_number, outcome, provider_request_id,
       provider_reference, failure_code, correlation_id, recorded_at
FROM payment_attempt_history
WHERE payment_id = '<payment-id>'
ORDER BY recorded_at, id;
```

Confirm that every row for a stage uses the same provider request ID, attempt numbers are bounded as expected, and the latest state agrees with the latest classified result. Preserve the correlation ID for structured-log and trace lookup. Attempt history is append-only; PostgreSQL rejects updates and deletes.

For an accounted payment, inspect the immutable ledger with the read-only statements in [`docs/sql/ledger-queries.sql`](sql/ledger-queries.sql). The payment history query shows every journal and compensating transaction; the balance queries aggregate debit and credit without changing stored rows. A `CAPTURE_ACCOUNTED` payment with no matching journal, a `CAPTURE_CONFIRMED` payment with a journal, an unbalanced transaction, or more than one capture journal is an integrity incident.

## Unknown outcome and crash recovery

The recovery use case follows this protocol:

1. Load the current payment and determine the active stage.
2. Append a `LOOKUP`/`STARTED` history event with the original correlation context.
3. Query the provider by stage and the persisted request ID. Do not open a database transaction around this network call.
4. If lookup confirms success or decline, persist that result and a lookup result event using optimistic locking.
5. If lookup returns `NOT_FOUND`, resume the active state and resend the equivalent payload with the same request ID. Normal temporary-failure bounds apply.
6. If lookup is unavailable, retain or enter the unknown state and try reconciliation later. Do not infer failure.
7. If lookup is contradictory or malformed, enter `FAILED` with a sanitized code and investigate.

This same path closes the crash window in which the provider committed success after the local `STARTED` event but the process stopped before saving the result.

## Capture accounting recovery

Ledger posting has one local `READ COMMITTED` transaction after provider success:

1. lock the payment row;
2. verify `CAPTURE_CONFIRMED` or a matching replay in `CAPTURE_ACCOUNTED`;
3. insert the journal header and entries;
4. transition payment to `CAPTURE_ACCOUNTED`;
5. append the canonical payment-captured outbox event through `messaging.api`; and
6. allow deferred balance/source validation to run at commit.

If the process stops before commit, PostgreSQL rolls back state, journal, and outbox; retry the same internal posting command. If commit completed but the response was lost, retry finds `CAPTURE_ACCOUNTED`, verifies the original outbox content, and returns the original journal. Same-payment calls serialize on the row lock, and unique source/payment/outbox keys prevent duplicate effects.

Do not interpret `CAPTURE_ACCOUNTED` as final order `COMPLETED`, final payment `CAPTURED`, or proof that Kafka has published the outbox event. If deferred validation or outbox append fails, do not disable triggers or patch rows: preserve the error and correlation ID, contain further financial processing, and fix the code/schema forward. If business evidence requires a correction, use the approved compensation command with a specific reason; never update or delete the posted transaction or entries. The current command creates one exact reversal and does not call the provider, remove the capture event, or imply a refund.

## Outbox inspection and delivery recovery

Use a read-only role to inspect a specific event or payment. Do not update status, attempt counts, leases, or timestamps.

```sql
SELECT event_id, aggregate_id, topic, event_key, event_type, schema_version,
       correlation_id, causation_id, occurred_at, status,
       cycle_attempt_count, total_attempt_count, available_at,
       lease_owner, lease_until, last_failure_code, last_failed_at, published_at
FROM outbox_events
WHERE event_id = '<event-id>' OR aggregate_id = '<payment-id>'
ORDER BY created_at, event_id;
```

`PENDING` is waiting for its next attempt. `IN_FLIGHT` is owned until `lease_until`; do not steal an unexpired lease. `PUBLISHED` means the broker acknowledged a send and the owner-guarded marker committed. `FAILED` means the 10-attempt cycle exhausted and needs investigation; no general outbox retry command exists yet.

The publisher claims with `SELECT ... FOR UPDATE SKIP LOCKED`, commits the lease, publishes outside PostgreSQL, and marks `PUBLISHED` only after broker acknowledgement. A process stop after acknowledgement but before the marker leaves an expired lease that will publish the same event ID again. This is expected at-least-once behavior; the notification inbox absorbs a matching duplicate.

## Notification and dead-letter recovery

The main listener makes one initial attempt plus three bounded blocking retries. After exhaustion, or immediately for non-retryable validation/integrity failures, it publishes to `ledgerflow.payment-captured.v1.dlt`; the source offset advances only after that publication is acknowledged. The DLT listener catalogs the original coordinates, bounded hash/size, sanitized failure, and allowlisted safe headers. It stores a validated canonical envelope/key only when safe to replay and never stores malformed raw poison bytes.

Inspect the catalog with a read-only role:

```sql
SELECT id, event_id, original_topic, original_partition, original_offset,
       payload_hash, payload_size, failure_code, failure_summary, attempt_count,
       status, replayable, replay_count, replay_available_at,
       replay_lease_owner, replay_lease_until, last_replay_failure_code,
       dead_lettered_at, replayed_at
FROM dead_letter_records
WHERE id = '<dead-letter-record-id>';
```

Only a row with `replayable = true` and eligible `OPEN` state may be replayed. Configure the usual runtime database and Kafka environment, then run:

```bash
scripts/replay-dead-letter \
  '<dead-letter-uuid>' \
  '<actor>' \
  '<specific reason of at least 10 characters>'
```

The script runs the application in non-web mode with Kafka listeners and the outbox publisher disabled. It claims the row with a lease, preserves the canonical envelope and order-ID Kafka key, removes old exception/delivery headers, generates a new replay request and transport correlation, injects a new W3C trace, waits for broker acknowledgement, and appends immutable `message_replay_audit` evidence. `REPLAYED` proves broker acknowledgement only; inbox deduplication may make consumption a successful no-op.

Relevant defaults and environment overrides are:

| Behavior | Environment variable | Default |
| --- | --- | --- |
| Main / DLT topic | `LEDGERFLOW_KAFKA_PAYMENT_CAPTURED_TOPIC` / `LEDGERFLOW_KAFKA_PAYMENT_CAPTURED_DLT_TOPIC` | `ledgerflow.payment-captured.v1` / `ledgerflow.payment-captured.v1.dlt` |
| Outbox enabled / batch / lease | `LEDGERFLOW_OUTBOX_PUBLISHER_ENABLED` / `LEDGERFLOW_OUTBOX_BATCH_SIZE` / `LEDGERFLOW_OUTBOX_LEASE_DURATION` | `false` / `25` / `30s` |
| Outbox attempts / base / cap / jitter | `LEDGERFLOW_OUTBOX_MAX_ATTEMPTS` / `LEDGERFLOW_OUTBOX_BASE_BACKOFF` / `LEDGERFLOW_OUTBOX_MAX_BACKOFF` / `LEDGERFLOW_OUTBOX_JITTER_RATIO` | `10` / `1s` / `256s` / `0.2` |
| Notification / DLT consumers | `LEDGERFLOW_NOTIFICATION_CONSUMER_ENABLED` / `LEDGERFLOW_NOTIFICATION_DLT_CONSUMER_ENABLED` | `false` / `false` |
| Consumer retry sequence | `LEDGERFLOW_NOTIFICATION_FIRST_RETRY_BACKOFF` / `LEDGERFLOW_NOTIFICATION_SECOND_RETRY_BACKOFF` / `LEDGERFLOW_NOTIFICATION_THIRD_RETRY_BACKOFF` | `1s` / `5s` / `30s` |
| Broker acknowledgement / replay lease | `LEDGERFLOW_KAFKA_ACK_TIMEOUT` / `LEDGERFLOW_REPLAY_LEASE_DURATION` | `10s` / `30s` |

Never edit outbox/inbox/DLT/audit rows, change consumer offsets, copy malformed payloads out of Kafka, or use an ad hoc producer to resend a message. If a catalog row is non-replayable or the audited command rejects it, contain and escalate rather than bypassing the guard.

## Retry policy and timeouts

The adapter configures both connection and whole-request timeouts. A connection failure before a request is established is classified temporary. HTTP `429`/`5xx` is also temporary. A read timeout or general I/O loss after sending is unknown because it may hide provider success.

Only confirmed temporary failures receive an automatic retry: at most one retry after the initial call, with exponential backoff, a maximum delay, and jitter. Declines, unknown outcomes, invalid responses, and optimistic-lock failures are not fed through that retry loop.

Relevant deployment properties are:

| Property | Default | Constraint |
| --- | ---: | --- |
| `LEDGERFLOW_PAYMENT_PROVIDER_BASE_URL` | unset | Trusted absolute HTTP(S) origin; required to create provider workflow beans |
| `LEDGERFLOW_PAYMENT_PROVIDER_CONNECT_TIMEOUT` | `1s` | Positive |
| `LEDGERFLOW_PAYMENT_PROVIDER_REQUEST_TIMEOUT` | `2s` | Positive |
| `LEDGERFLOW_PAYMENT_PROVIDER_MAX_ATTEMPTS` | `2` | `1` or `2`; includes the initial call |
| `LEDGERFLOW_PAYMENT_PROVIDER_BASE_BACKOFF` | `200ms` | Positive |
| `LEDGERFLOW_PAYMENT_PROVIDER_MAX_BACKOFF` | `1s` | At least base backoff |
| `LEDGERFLOW_PAYMENT_PROVIDER_BACKOFF_MULTIPLIER` | `2.0` | At least `1.0` |
| `LEDGERFLOW_PAYMENT_PROVIDER_JITTER_RATIO` | `0.2` | From `0.0` through `1.0` |

Timeouts must be chosen below the upstream request budget while allowing the approved provider latency. Production TLS, authentication, egress allowlisting, and provider-specific reconciliation remain prerequisites for a real-provider milestone.

## Escalation and prohibited actions

Escalate immediately when a payment remains active/unknown beyond the provider's reconciliation objective, lookup gives contradictory evidence, the same request ID appears with different payloads, history mutation is attempted, or optimistic conflicts repeat without convergence.

Include only payment/order IDs, state, sanitized failure code, timestamps, correlation/trace IDs, and provider request/reference IDs. Never include the payment-method reference, bearer token, database credentials, raw provider payload, or idempotency key.

Do not:

- change `payments.state`, `version`, request IDs, or provider references with SQL;
- update/delete `payment_attempt_history` or disable its trigger;
- create a new request ID to work around uncertainty;
- retry a confirmed decline;
- resend provider capture after `CAPTURE_CONFIRMED` or `CAPTURE_ACCOUNTED`;
- update/delete ledger transactions or entries, disable ledger triggers, or alter a posted account identity;
- change outbox/inbox/notification/DLT/audit rows or Kafka offsets directly;
- resend an event outside `scripts/replay-dead-letter` or change its envelope/key;
- repair a financial error in place instead of appending an approved correction; or
- describe `CAPTURE_CONFIRMED` as accounted, or `CAPTURE_ACCOUNTED` as final order/payment completion or proof of Kafka publication.

If the tested recovery use case is not available through an approved secured operational entry point, contain and escalate rather than improvising direct database or provider changes.
