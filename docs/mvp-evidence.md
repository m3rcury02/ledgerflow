# LedgerFlow MVP Proof and Acceptance Evidence

- Release candidate: `v1.0.0-mvp`
- Evidence date: 2026-07-17
- Scope: reproducible local and Testcontainers demonstration, not a production certification

This is the release evidence index. `scripts/smoke-test` proves one normal public journey;
`scripts/demo-mvp` exercises the focused failure and recovery scenarios; `./gradlew --no-daemon
clean verify --console=plain` remains the complete repository gate. Test names below are stable
evidence identifiers, and this document is the durable acceptance record.

## Five-minute interview demonstration

With JDK 25 and a healthy Docker runtime, run:

```bash
make smoke-test
```

The test starts ephemeral PostgreSQL 18 and Kafka 4.3 containers, calls the authenticated public
HTTP API, observes synchronous provider authorization/capture, proves one balanced immutable
journal and one durable outbox event, publishes it, and waits for one idempotent notification. It
then proves byte-equivalent replay, changed-payload conflict, stable provider call counts, and the
truthful asynchronous boundary. On a warmed developer machine this is intended to fit a short
interview walkthrough; its duration is not an SLO.

Use the remaining time to show:

1. `docs/architecture.md` for the boundary and sequence diagrams.
2. `application/src/main/openapi/ledgerflow.yaml` for the contract-first HTTP surface.
3. `V003__create_immutable_ledger.sql` and `V004__create_transactional_outbox.sql` for database
   invariants and atomic outbox persistence.
4. `docs/observability.md` for the single-trace and bounded-metric model.
5. `docs/threat-model.md` for object authorization, replay, and data-minimization controls.

For the broader automated demonstration run `make demo-mvp`. For the reusable nine-service local
platform run `make dev-up`; the automated proof does not depend on or mutate that environment.

## Required failure and recovery scenarios

All rows are automated integration or unit evidence and are part of `make demo-mvp`, except where
the command column explicitly names the complete `verify` gate. “Passed” means the named evidence
passed in the 2026-07-17 release validation; it is not a throughput, availability, or production
deployment claim.

| Scenario | Evidence | Result |
| --- | --- | --- |
| Successful completed order | `LedgerFlowMvpEndToEndTest#completedPublicOrderReplaysExactlyAndDeliversOneAsynchronousNotification` | Passed |
| Exact HTTP replay | Same end-to-end test; compares the complete body and stable provider/row counts | Passed |
| Changed-payload idempotency conflict | Same end-to-end test; expects `409 idempotency_key_reused` | Passed |
| Authorization decline | `PublicOrderWorkflowIntegrationTest#exposesAuthorizationAndCaptureDeclinesTruthfullyWithoutFinancialEffects` | Passed |
| Capture decline | Same decline test, including no journal/outbox effect | Passed |
| Temporary provider failure and bounded retry | `PaymentProviderIntegrationTest#retriesOnlyATemporaryAuthorizationFailureWithinTheConfiguredBound` and `#stopsRetryingATemporaryFailureAtTheConfiguredAttemptLimit` | Passed |
| Timeout with provider-confirmed recovery | `PublicOrderWorkflowIntegrationTest#reconcilesTimedOutProviderSuccessByLookupWithoutResend` and `#reconcilesTimedOutCaptureSuccessByLookupWithoutResend` | Passed |
| Timeout with `NOT_FOUND` and safe same-ID resend | `PublicOrderWorkflowIntegrationTest#resendsOnlyTheSameOperationIdAfterTimedOutLookupReturnsNotFound` and `#captureTimeoutNotFoundResendsOnlyTheStableCaptureOperation` | Passed |
| Provider success followed by local crash | `PaymentRecoveryIntegrationTest#recoversWhenTheProcessCrashesAfterProviderSuccessBeforeLocalPersistence` and `CaptureFinalizationIntegrationTest#publicWorkflowRecoversProviderSuccessLostBeforeLocalPersistence` | Passed |
| Database rollback during financial finalization | `TransactionalOutboxIntegrationTest#eachFinancialFinalizationMutationFailureRollsBackTheWholeTransaction`, parameterized over journal header, entries, payment transition, and outbox insert | Passed |
| Kafka unavailable with durable outbox recovery | `WorkflowKafkaUnavailableIntegrationTest#runtimeKafkaOutageCannotRollbackCompletedBusinessStateOrDurableOutbox` | Passed |
| Broker acknowledgement followed by publisher crash | `OutboxPublisherIntegrationTest#brokerAcknowledgementThenCrashRepublishesButInboxPreventsDuplicateNotification` | Passed |
| Duplicate Kafka delivery | Same publisher-crash test proves repeated event ID, one inbox row, and one notification | Passed |
| Semantic duplicate under a different event ID | `KafkaRetryAndDltIntegrationTest#reEnvelopedSemanticDuplicateCreatesOneNotification` | Passed |
| Poison event to DLT | `KafkaRetryAndDltIntegrationTest#retriesTransientFailureThreeTimesThenCatalogsAndAuditsSafeReplay` | Passed |
| Malformed DLT terminal disposition | `KafkaRetryAndDltIntegrationTest#malformedDirectDltRecordDoesNotStarveTheNextRecord` | Passed |
| DLT publication failure | `KafkaCommitFailureIntegrationTest#dltPublicationFailureRetainsTheSourceRecordUntilPublicationRecovers` | Passed |
| Consumer database commit followed by offset failure | `KafkaCommitFailureIntegrationTest#databaseCommitFollowedByOffsetFailureRedeliversWithoutDuplicateSideEffect` | Passed |
| Operator payment retry | `OperatorPaymentRecoveryIntegrationTest#lookupFirstRecoveryReusesProviderIdentityAndFinalizesFinancialEffectsExactlyOnce` | Passed |
| Operator outbox retry | `OperatorApiIntegrationTest#retryCommandIsIdempotentAuditedAndResetsOnlyTheExistingOutboxRecord` | Passed |
| Operator DLT replay | `KafkaRetryAndDltIntegrationTest#retriesTransientFailureThreeTimesThenCatalogsAndAuditsSafeReplay`, combined with operator command/security evidence in `OperatorApiIntegrationTest` | Passed |
| Retry-worker lease takeover | `OperatorRetryIntegrationTest#expiredLeaseAllowsTakeoverAndRejectsStaleCompletion` | Passed |
| Telemetry-backend outage | `TelemetryFailureIsolationIntegrationTest#exporterFailureDoesNotChangeTheBusinessTransaction` | Passed |
| Graceful shutdown with in-flight work | `DrainableWorkTrackerTest#shutdownStopsNewWorkAndWaitsForInFlightWorkBeforeCompleting` and `#aBoundedDrainTimeoutIsExposedInsteadOfSilentlyDiscardingWork` | Passed |
| Unauthorized customer and operator actions | `OrderHttpIntegrationTest#requiresAuthenticationAndTheCorrectScope` and `OperatorApiIntegrationTest#enforcesTheOperatorBoundaryAndSeparatePermissions` | Passed |

The DLT replay row is deliberately described as composite evidence: the Kafka integration test
proves identity-preserving replay and the operator tests prove authenticated, idempotent command
admission and leasing. It does not claim an external Keycloak/browser demonstration.

## Product acceptance map

| Criterion | Evidence | Result |
| --- | --- | --- |
| AC-001 successful completed workflow | `LedgerFlowMvpEndToEndTest` plus ledger SQL assertions | Passed |
| AC-002 exact replay | `LedgerFlowMvpEndToEndTest` and `OrderHttpIntegrationTest#createsAndReplaysTheOriginalResponse` | Passed |
| AC-003 changed request conflict | `LedgerFlowMvpEndToEndTest` | Passed |
| AC-004 concurrent create/resume | `OrderIdempotencyIntegrationTest` and `CaptureFinalizationIntegrationTest#concurrentWorkflowResumptionsConvergeWithoutDuplicateProviderOrFinancialEffects` | Passed by `clean verify` |
| AC-005 authorization decline | `PublicOrderWorkflowIntegrationTest` | Passed |
| AC-006 latency, bounded temporary retry, retry-pending | `PaymentProviderIntegrationTest` and `PublicOrderWorkflowIntegrationTest` | Passed |
| AC-007 state and optimistic concurrency | module state-machine unit tests plus `PaymentConcurrencyIntegrationTest` | Passed by `clean verify` |
| AC-008 database ledger rejection | `LedgerConstraintIntegrationTest` | Passed by `clean verify` |
| AC-009 financial transaction fault injection | parameterized `TransactionalOutboxIntegrationTest` | Passed |
| AC-010 publication crash and duplicate suppression | `OutboxPublisherIntegrationTest` | Passed |
| AC-011 bounded retry, DLT, and operator visibility | `KafkaRetryAndDltIntegrationTest` and `OperatorApiIntegrationTest` | Passed |
| AC-012 idempotent operator retry | `OperatorApiIntegrationTest`, `OperatorPaymentRecoveryIntegrationTest`, and `OperatorRetryIntegrationTest` | Passed |
| AC-013 trace and correlation propagation | `TracePropagationIntegrationTest` and `ObservabilityIntegrationTest` | Passed by `clean verify` |
| AC-014 customer/object/operator authorization | `OrderHttpIntegrationTest` and `OperatorApiIntegrationTest` | Passed |
| AC-015 full quality lifecycle | `./gradlew --no-daemon clean verify --console=plain` | Passed; see validation evidence below |
| AC-016 provider protocol failure | `PublicOrderWorkflowIntegrationTest#persistsFailedOrderAndReplaysSanitizedProviderProtocolProblem` | Passed |

## Manual platform walkthrough

The local platform is useful for an operator-oriented demonstration, but it is not required for
the deterministic acceptance proof:

```bash
make dev-up
make dev-status
make observability-check
```

Provision local Keycloak identities outside Git, run a test-provider implementation of the
versioned fixture contract, start the application with the documented environment variables, and
use the request examples in `docs/api-design.md`. `make demo-observability` prints the trace and
correlation IDs and verifies Tempo/Loki lookup. Never persist tokens in scripts, shell history,
documentation, or evidence files.

## Interpretation limits

- Testcontainers prove behavior against real protocols on one development host, not production
  topology, failover, capacity, backups, or disaster recovery.
- Local timings and provisional SLO queries are demonstration evidence, not guarantees.
- Kafka publication and consumption are at-least-once. The local database effects are made
  idempotent; LedgerFlow does not claim end-to-end exactly-once delivery.
- The mock provider demonstrates recovery semantics and is not a real payment integration.
- Open risks and production gates are in `docs/security/mvp-residual-risk-register.md` and
  `docs/operational-limitations.md`.
