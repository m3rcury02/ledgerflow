# LedgerFlow Failure-Injection Guide

Failure injection exists only in `local`, `test`, and `integration-test` profiles. Startup rejects
enabled controlled faults in a production environment. There is no public fault-injection HTTP
endpoint, and no test fixture is packaged as the payment provider.

## Deterministic provider scenarios

The integration-test provider accepts only opaque `pm_mock_*` references:

| Reference | Demonstrated behavior |
| --- | --- |
| `pm_mock_success` | authorization and capture succeed |
| `pm_mock_authorization_decline` | confirmed authorization decline; never retried |
| `pm_mock_capture_decline` | confirmed capture decline; never retried |
| `pm_mock_temporary_error` | first call fails temporarily, then succeeds within the bound |
| `pm_mock_persistent_temporary_error` | all configured attempts fail; workflow becomes retry-pending |
| `pm_mock_authorization_timeout` / `pm_mock_capture_timeout` | provider persists success but client times out; lookup confirms it |
| `pm_mock_authorization_timeout_not_found` / `pm_mock_capture_timeout_not_found` | first timed-out call has no provider effect; lookup returns `NOT_FOUND`, then the same operation ID is resent |
| `pm_mock_slow_response` | bounded latency without a database transaction across HTTP |
| `pm_mock_invalid_response` | malformed protocol outcome; persisted sanitized failure and no financial effect |

These values are test tokens, not card data or credentials.

## Controlled fault points

Set all of the following only in a local/test profile:

```bash
export LEDGERFLOW_FAULT_INJECTION_ENABLED=true
export LEDGERFLOW_FAULT_INJECTION_POINT=OUTBOX_PUBLISH
export LEDGERFLOW_FAULT_INJECTION_MODE=FAIL
```

The allowlist is:

- `PAYMENT_PROVIDER` — fail or delay immediately before provider I/O;
- `OUTBOX_PUBLISH` — fail or delay a leased publisher attempt;
- `NOTIFICATION_CONSUME` — fail or delay before notification validation/persistence;
- `NOTIFICATION_OFFSET_COMMIT` — fail after notification database commit but before listener
  return, modeling an offset acknowledgement failure; and
- `NOTIFICATION_DLT_PUBLISH` — fail after terminal classification and before DLT publication,
  proving the source offset remains recoverable.

`FAIL` throws a stable injected-fault exception. `DELAY` uses the configured bounded duration and
preserves thread interruption. Disable the variables and restart to recover. Never enable these
settings on shared or production infrastructure.

## Database and network faults

PostgreSQL trigger fixtures reject each capture-finalization write in turn and prove the whole
local transaction rolls back. Toxiproxy Testcontainers exercise provider latency/reset/timeout,
temporary database unavailability, and Kafka unavailability without adding production proxies.

Run the focused proofs:

```bash
./gradlew --no-daemon :application:integrationTest \
  --tests '*TransactionalOutboxIntegrationTest' \
  --tests '*KafkaCommitFailureIntegrationTest' \
  --tests '*DependencyToxiproxyIntegrationTest' \
  --tests '*PaymentProviderToxiproxyIntegrationTest' \
  --console=plain
```

## Safe interpretation

Injection is placed at named boundaries, not arbitrary source lines. The database probe proves
atomicity for the current four mutation stages; migration or orchestration changes must update the
parameterized proof. Kafka failure tests prove durable retry and duplicate-safe effects, not that a
broker is always available. See `docs/mvp-evidence.md` for exact scenario mappings and
`docs/runbook.md` for operational recovery.
