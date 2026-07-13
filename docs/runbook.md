# Payment and Ledger Recovery Runbook

- Status: Milestone 5A development runbook
- Last updated: 2026-07-13

## Scope and current limitation

This runbook covers authorization/capture failures, crash recovery, and non-public ledger accounting for provider-confirmed captures. Milestone 5A provides tested module use cases but intentionally exposes no public or operator endpoint: final order and outbox effects do not exist yet. The secured inspection/retry API and operator audit trail remain later work.

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
| `CAPTURE_ACCOUNTED` | Exactly one matching balanced capture journal committed with this state | Treat ledger posting as complete; a repeated internal posting returns the original journal |
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
4. transition payment to `CAPTURE_ACCOUNTED`; and
5. allow deferred balance/source validation to run at commit.

If the process stops before commit, PostgreSQL rolls back both state and journal; retry the same internal posting command. If commit completed but the response was lost, retry finds `CAPTURE_ACCOUNTED` and returns the original journal. Same-payment calls serialize on the row lock, and unique source/payment indexes prevent duplicate journal transactions.

Do not interpret `CAPTURE_ACCOUNTED` as final order completion or an emitted event. If deferred validation fails, do not disable triggers or patch rows: preserve the error and correlation ID, contain further financial processing, and fix the code/schema forward. If business evidence requires a correction, use the approved compensation command with a specific reason; never update or delete the posted transaction or entries. The current command creates one exact reversal and does not call the provider or imply a refund.

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
- repair a financial error in place instead of appending an approved correction; or
- describe `CAPTURE_CONFIRMED` as accounted, or `CAPTURE_ACCOUNTED` as final order/outbox completion.

If the tested recovery use case is not available through an approved secured operational entry point, contain and escalate rather than improvising direct database or provider changes.
