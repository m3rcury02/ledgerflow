# Close Five Production Abuse-Case Gaps

## Metadata

- Status: Proposed
- Owner: Unassigned; the maintainer assigns an owner when approving a milestone
- Created: 2026-07-14
- Last updated: 2026-07-15
- Approved by: None
- Approval date: None
- Current milestone: None; this document is a supporting evidence/design record, not an execution authority

`docs/plans/mvp-execplan.md` is the sole authority for milestone ordering, approval, progress, and
completion. Its approved Milestone 5D incorporates this record's R1 and R2 designs. R3 and R4 remain
separately tracked Proposed follow-ups in the canonical plan; R5 is retained only as the original
combined validation design. The milestone labels below do not independently authorize work, and
their statuses must not be used to infer approval. This document remains the evidence, detailed
finding design, compatibility analysis, and anticipated touch-set reference; do not duplicate it in
another ExecPlan.

## Purpose and outcome

Close only the five verified abuse cases named in this plan:

1. unauthenticated health probes can repeatedly allocate database and Kafka work;
2. a payment-captured event with a new event ID can create another notification for the same
   semantic effect;
3. malformed DLT routing headers can make one record retry indefinitely and starve its partition;
4. orders and HTTP idempotency records have no durable storage budget or retention policy; and
5. DLT replay trusts a caller-supplied actor and has no cooldown or attempt limit.

When the approved milestones are complete, management traffic is isolated from the application
port, expensive readiness work is coalesced, transport and business-effect idempotency are
independent database invariants, terminal malformed DLT input advances only after sanitized
evidence is durable, order growth is quota-bound, the documented idempotency window is retained
safely, and replay identity and authority come from a validated JWT with bounded normal and
break-glass attempts.

This is a production-readiness remediation, not a general security rewrite or completion of the
remaining MVP business flow.

## Verified pre-remediation state (2026-07-14)

The original investigation was read-only. The working tree was clean before this plan was created. The
reported abuse review is not stored as a repository file; the five maintainer-supplied findings
were checked directly against current source, migrations, configuration, tests, and operational
documentation.

### Verified behavior and deployment impact

| Finding | Evidence at review time | Did the behavior exist? | Deployment impact |
| --- | --- | --- | --- |
| Actuator exhaustion | OrderSecurityConfiguration permits health and info without authentication on the application security chain. application.yaml has no separate management port. OperationalHealthIndicator calls PostgreSQL and optionally Kafka for readiness. DependencyProbe creates and closes a Kafka Admin on every Kafka probe. | Yes | Blocker for a publicly routed production deployment unless an independently verified management-only network boundary already prevents access. The code and deployment contract must still be corrected before LedgerFlow claims a safe default. |
| Re-enveloped event duplication | JdbcNotificationStore.process deduplicates notification_inbox only by event_id and notifications has uniqueness only on event_id. NotificationEventValidator validates envelope relationships but cannot prove producer origin. A new event ID with otherwise equivalent capture data inserts another notification. | Yes | Blocker before enabling the production notification consumer. Producer ACLs reduce who can exploit it but do not replace a business-side-effect invariant. |
| Malformed DLT partition blocking | DeadLetterCatalogListener parses original topic, partition, and offset before entering its malformed-payload handling. Missing or malformed headers throw. The DLT DefaultErrorHandler retries and its exhausted recoverer always throws while ackAfterHandle is false, so the offset is not committed. | Yes | Blocker before enabling the production DLT catalog consumer. One poison record can indefinitely prevent later records on the same partition. |
| Unbounded resource growth | V001 gives orders and idempotency_records no cleanup or quota structures. OrderService creates one of each for every valid new key. WriteRateLimiter is per-instance, resets on restart, and limits request rate rather than durable storage. Product requirements explicitly retain idempotency rows indefinitely. | Yes | Blocker before sustained or multi-tenant public production. It is not a blocker for a disposable local demonstration. |
| Caller-asserted, unbounded replay | scripts/replay-dead-letter accepts actor as an argument. ReplayCommandConfiguration reads ledgerflow.replay.actor. DeadLetterReplayService validates only its syntax. JdbcNotificationStore.claimReplay increments replay_count without a maximum, and markReplayFailed makes the record immediately eligible again. | Yes | Blocker before production replay is enabled. A customer-only deployment can proceed only if replay is disabled and the replay Kafka credential has no publish authority. Full MVP operator recovery remains blocked. |

### Controls already valid at review time

- The application already validates JWT issuer, audience, signature algorithm, scopes, and
  allowlisted roles for active HTTP routes.
- The existing order primary key on principal_scope, operation, and key_hash correctly serializes
  same-key creation and prevents two concurrent orders.
- notification_inbox event-ID and payload-hash checks correctly provide transport-level
  deduplication for redelivery of the same event ID.
- Main-consumer retries and DLT publication are bounded; the defect is in consuming malformed
  records from the DLT, not in forwarding a failed main-topic record to it.
- dead_letter_records and message_replay_audit preserve immutable source and audit evidence.
- No H2, cache-backed idempotency, new application datastore, or exactly-once delivery claim is
  present.

### Tests inspected before remediation

- modules/operations/src/test/java/com/ledgerflow/operations/internal/OperationalHealthIndicatorTest.java
  proves sanitized dependency failure and drain status, but not port separation, request
  coalescing, or managed Kafka-client reuse.
- application/src/integrationTest/java/com/ledgerflow/notifications/KafkaRetryAndDltIntegrationTest.java
  proves main-topic poison handling, same-event-ID hash conflict, bounded retries, replay, and
  immutable audit. It does not send a record directly to the DLT with missing or malformed
  original-routing headers and does not test a new event ID for an existing semantic effect.
- modules/notifications/src/test/java/com/ledgerflow/notifications/internal/kafka/NotificationEventValidatorTest.java
  proves envelope/header relationships but defines no semantic-effect identity.
- application/src/integrationTest/java/com/ledgerflow/orders/OrderIdempotencyIntegrationTest.java
  proves concurrent same-key behavior and payload conflict, but not bounded lock wait, retention,
  or quota behavior.
- application/src/integrationTest/java/com/ledgerflow/orders/OrderRepositoryIntegrationTest.java
  proves V001 constraints and hash-only key storage, but not cleanup or usage counters.
- application/src/integrationTest/java/com/ledgerflow/orders/OrderHttpIntegrationTest.java and
  modules/orders/src/test/java/com/ledgerflow/orders/internal/web/WriteRateLimiterTest.java prove
  per-instance request limiting only.

## Scope and non-goals

### Design scope

- A separate configurable Spring Boot management port with a dedicated management security
  boundary, status-only unauthenticated liveness/readiness responses, and deployment requirements
  that prohibit public ingress to that port.
- A long-lived managed Kafka Admin used by dependency probes plus a short, bounded readiness-result
  cache that coalesces concurrent requests.
- A versioned semantic notification-effect identity derived by trusted application code from the
  payment-capture domain data, with database uniqueness and content-conflict detection.
- Durable terminal evidence for malformed DLT records keyed by actual DLT topic, partition, and
  offset, followed by normal record acknowledgement.
- A documented idempotency retry window, bounded multi-instance cleanup of expired completed
  idempotency rows, an authoritative per-customer order quota, bounded lock wait, and low-cardinality
  growth metrics.
- A narrow contract-first dead-letter inspection and replay HTTP boundary. Inspection and replay
  use separate scopes. Replay actor identity is derived from the authenticated JWT. Normal replay,
  cooldown, command idempotency, and break-glass behavior are database guarded and audited.
- Flyway migrations V006 through V009. Existing migrations V001 through V005 are never edited.
- Focused unit, PostgreSQL Testcontainers, Kafka Testcontainers, HTTP security, concurrency, and
  abuse-case tests plus the complete verification lifecycle.
- Architecture, API, data, product, threat, runbook, deployment-security, ADR, README, and example
  environment documentation needed to describe these controls accurately.

### Non-goals

- Payment or final order orchestration, final payment or order states, notification delivery, new
  event types, refunds, operator UI, or the general failed_operations API from the proposed MVP
  plan.
- Replacing Kafka, the transactional outbox, the event envelope, Spring Security, or PostgreSQL.
- Adding Redis or another datastore for quotas, idempotency, health caching, or replay.
- Authenticating Kubernetes probes with OAuth. Probe endpoints remain status-only and are protected
  by the management-port network boundary.
- A quarantine topic in this plan. Durable PostgreSQL terminal evidence is sufficient for malformed
  DLT input. A restricted quarantine topic requires a separately approved operational need and ACL
  design.
- Deleting orders, notifications, inbox rows, outbox rows, dead-letter evidence, or replay audit.
- Retention policies for financial, outbox, inbox, notification, DLT, audit, log, or trace data
  other than the HTTP idempotency records explicitly addressed here.
- Production cloud infrastructure. The deployment document specifies portable ingress and network
  policy requirements and may include an illustrative Kubernetes policy, but it is not full IaC.
- Unrelated package moves, security-framework rewrites, dependency upgrades, formatting churn, or
  changes to the active MVP ExecPlan.

## Assumptions and unresolved decisions

### Facts and working assumptions

- No repository abuse-review artifact exists. The five findings in the maintainer request are the
  review input, and the Current state evidence is the verification record.
- This plan has no access to a production LedgerFlow database. Migration preflight therefore treats
  existing semantic duplicates or unmappable notifications as possible and fails without changing
  them.
- The initial production deployment platform is still undecided. The management boundary is
  expressed as portable ingress/firewall requirements plus an illustrative Kubernetes
  NetworkPolicy, not provider-specific IaC.
- Production JWTs have a validated issuer and subject. An authorized client identifier such as azp
  may be absent and is optional audit context; issuer plus subject is the actor identity.
- The current single deployable process may use one Kafka credential whose ACL is the union of
  enabled adapters. The preferred production deployment uses separate credentials where the runtime
  topology permits it. Neither case permits wildcard topics or Kafka administration.
- The identity provider controls subject issuance. The per-subject order quota bounds one customer's
  storage but does not defend against a compromised issuer minting unlimited subjects; issuer
  governance and ingress controls remain separate deployment responsibilities.

### Proposed choices that approval must select or revise

- Management port 8081 and health-probe cache TTL two seconds.
- Supported idempotency retry window seven days and physical retention eight days.
- All-time customer order quota 10,000 and lock timeout two seconds.
- Normal replay maximum three, failure cooldown five minutes, and two additional one-approval
  break-glass attempts.
- No quarantine topic.

A milestone approval selects only the choices used by that milestone. If a maintainer wants a
different window, quota, port, cooldown, cap, semantic identity, quarantine path, or deployment
trust boundary, revise and reapprove the affected milestone before code changes.

### Decisions intentionally left outside this plan

- How an operator requests a customer-specific quota increase.
- Retention or archival for orders and every table other than idempotency_records.
- The alerting platform, paging destination, numerical lag threshold, and production SLO.
- The concrete cloud load balancer, service mesh, firewall, or NetworkPolicy packaging.
- Separating the monolith's Kafka credentials into independently deployed worker identities.
- General payment/outbox recovery and a unified failed-operations API.

## Interfaces and data

### HTTP interfaces

The order contract remains backward compatible except for an explicit time-bound idempotency
guarantee and a documented order-quota error.

- POST /api/v1/orders continues to require Idempotency-Key.
- The same key and canonical request is guaranteed to replay, and a conflicting request is
  guaranteed to return 409, for seven complete days after the original response completes.
- The implementation retains completed idempotency records for eight days by default, giving one
  day of safety margin beyond the supported retry window.
- A new order that would exceed the authenticated subject's durable quota returns 422 with code
  order_quota_exceeded. An existing idempotent result still replays after the quota is full.
- A contender whose database lock wait exceeds the configured bound returns retryable 503 with
  Retry-After and must retry with the same key.

Add only these operator operations to application/src/main/openapi/ledgerflow.yaml:

- GET /api/v1/operator/dead-letters/{deadLetterRecordId}, requiring
  ledgerflow.operations.read and operator or admin. It returns a sanitized projection only.
- POST /api/v1/operator/dead-letters/{deadLetterRecordId}/replays, requiring
  ledgerflow.operations.retry and operator or admin, Idempotency-Key, and a 10 to 500 character
  reason.
- POST /api/v1/operator/dead-letters/{deadLetterRecordId}/break-glass-replays, requiring
  ledgerflow.operations.break-glass and admin, Idempotency-Key, a reason, and a bounded external
  approval reference.

Neither replay operation accepts actor, topic, event key, payload, event ID, destination, attempt
limit, or cooldown. The server selects all of them.

The ordinary replay endpoint is available only below the normal attempt maximum. The break-glass
endpoint is available only after that maximum and grants one attempt per unique approval reference,
subject to the absolute break-glass cap. HTTP idempotency is scoped by authenticated issuer,
subject, operation, and hashed key; changed request content returns 409.

### Java and module interfaces

- OperationsConfiguration owns one managed Kafka Admin bean and closes it at shutdown.
  DependencyProbe uses that bean; it no longer calls Admin.create per request.
- OperationalHealthIndicator consumes a coalesced dependency snapshot. StartupDependencyValidator
  uses an uncached probe so startup cannot pass on stale readiness data.
- A dedicated management-context SecurityFilterChain owns Actuator authorization. The order
  application chain removes every Actuator matcher and continues to deny unmatched application-port
  requests.
- The notifications module adds a small NotificationEffectIdentity value derived only after the v1
  event validates. For PAYMENT_CAPTURED it uses effect type PAYMENT_CAPTURED_NOTIFICATION,
  effect-identity version 1, and the immutable ledgerTransactionId as the effect key. The semantic
  content compared on collision is order ID, payment ID, ledger transaction ID, provider-capture
  causation ID, amount, currency, and captured-at instant. Event ID, transport correlation, and
  trace headers are deliberately excluded.
- A future event contract must define its own effect type, identity version, key, and compared
  content. Merely changing an event ID or schema version must not bypass the same business effect,
  while legitimate distinct future captures with distinct ledger transaction identities remain
  possible.
- JdbcNotificationStore retains event-ID and payload-hash transport checks. A new event ID with an
  existing matching semantic effect records the inbox event and performs no second notification
  insert. The same semantic identity with conflicting compared content raises
  NotificationIntegrityException and follows the direct-DLT path.
- DeadLetterCatalogListener treats input-contract failures as terminal data, not transient
  dependency failures. JdbcNotificationStore adds an idempotent terminal-evidence insert using
  actual DLT coordinates. Database failures still propagate so the record is never committed before
  evidence is durable.
- The current DeadLetterReplay.replay method that accepts actor text is removed. The replacement
  service accepts only an internal trusted actor value built at the JWT boundary. The HTTP adapter
  derives issuer, subject, authorized client identifier when present, role, and scope from
  JwtAuthenticationToken.
- The existing replay script becomes an HTTP client. It reads a bearer token from the
  LEDGERFLOW_OPERATOR_TOKEN environment variable, never accepts an actor argument, never prints the
  token, and cannot choose Kafka data.

No new external production library is required. Existing Spring Boot-managed Web MVC, OAuth
resource-server, JDBC, Kafka, Actuator, Micrometer, and Jackson capabilities are sufficient. The
notifications project may need direct declarations of already-present Spring Web MVC and Security
APIs because that module will compile the operator adapter; this is module dependency accuracy, not
a new runtime technology.

### Database migrations

Create these migrations in order:

1. application/src/main/resources/db/migration/V006__add_notification_semantic_effect_identity.sql
   adds effect_type, effect_identity_version, effect_key, source_causation_id, and
   source_occurred_at to notifications; backfills them by joining the existing capture ledger,
   payment, and outbox evidence; verifies every row can be mapped; rejects pre-existing duplicate
   semantic identities rather than deleting evidence; makes the fields non-null; and adds a unique
   constraint on effect_type, effect_identity_version, and effect_key. It also adds a constrained
   processing_outcome to notification_inbox so APPLIED and SEMANTIC_DUPLICATE transport records are
   distinguishable.
2. application/src/main/resources/db/migration/V007__record_terminal_dlt_evidence.sql creates
   terminal_dlt_records. It stores consumer name, actual DLT topic/partition/offset, key hash and
   size, payload hash and size, allowlisted safe headers, a stable terminal failure code and bounded
   summary, and observed_at. It stores no raw key, payload, invalid original-routing values, stack
   trace, or exception message. A unique constraint on consumer name plus actual DLT coordinates
   makes redelivery idempotent. A trigger rejects update and delete.
3. application/src/main/resources/db/migration/V008__bound_order_and_idempotency_growth.sql creates
   customer_order_usage with one row per owner subject, non-negative order_count, positive
   order_limit, version, and updated_at, backfilled from orders. The limit is the greater of the
   proposed default and the existing exact count, so migration never falsifies or discards usage and
   an already-over-limit subject cannot create more. It adds the index needed for bounded
   completed-idempotency cleanup on completed_at and primary-key tie breakers. Existing order and
   idempotency rows are not deleted by migration.
4. application/src/main/resources/db/migration/V009__authenticate_and_bound_dlt_replay.sql creates
   operator_replay_commands with scoped hashed idempotency keys, request hashes, status, lease,
   immutable request fields, and response snapshot. It extends dead_letter_records with standard
   and break-glass attempt evidence and cooldown state. It extends message_replay_audit with
   identity_source, actor_issuer, actor_subject, actor_client_id, break_glass, and approval_reference.
   Existing rows are backfilled as LEGACY_CALLER_ASSERTED; new OIDC_JWT audit rows require
   server-derived issuer and subject. The existing append-only trigger continues to reject update
   and delete.

Migration V006 must abort if an existing notification cannot be tied to one immutable capture
ledger transaction or if more than one existing notification has the same proposed semantic
identity. Migration code must not choose a winner or delete a duplicate. Such data is an integrity
incident requiring an incident-specific, separately reviewed forward reconciliation migration
before V006 is retried.

### Operational configuration

Add and validate these configuration values. Durations must be positive and counts must have small
documented upper bounds.

| Environment variable | Proposed default | Constraint and purpose |
| --- | ---: | --- |
| LEDGERFLOW_MANAGEMENT_PORT | 8081 | Must differ from server.port outside tests; route only from kubelet/monitoring networks |
| LEDGERFLOW_HEALTH_PROBE_CACHE_TTL | 2s | 250ms to 10s; caches success and failure and coalesces concurrent probes |
| LEDGERFLOW_IDEMPOTENCY_RETRY_WINDOW | 7d | Publicly supported replay/conflict window |
| LEDGERFLOW_IDEMPOTENCY_RETENTION | 8d | Must be at least the retry window plus the configured cleanup interval |
| LEDGERFLOW_IDEMPOTENCY_CLEANUP_INTERVAL | 1h | Bounded scheduled cleanup cadence |
| LEDGERFLOW_IDEMPOTENCY_CLEANUP_BATCH_SIZE | 1000 | 1 to 5000 rows per transaction |
| LEDGERFLOW_ORDER_MAX_PER_CUSTOMER | 10000 | Durable all-time order budget per authenticated subject |
| LEDGERFLOW_ORDER_LOCK_TIMEOUT | 2s | 100ms to 5s; bounds idempotency and quota row contention |
| LEDGERFLOW_REPLAY_MAX_ATTEMPTS | 3 | Normal attempts per DLT record |
| LEDGERFLOW_REPLAY_COOLDOWN | 5m | Delay after a failed publication before any next attempt |
| LEDGERFLOW_REPLAY_MAX_BREAK_GLASS_ATTEMPTS | 2 | Absolute number of additional approved attempts; total default cap is five |
| LEDGERFLOW_REPLAY_COMMAND_LEASE | 30s | Must exceed Kafka acknowledgement timeout |

Approval of the affected milestone selects these defaults. A maintainer who wants different product
windows or quotas must revise the plan before implementation; an implementer must not silently pick
new values.

### Deployment and Kafka authorization contract

Create docs/deployment-security.md and link it from the README and runbook. It must state:

- the application port is the only customer/operator ingress target;
- the management port has no public load balancer or public ingress route;
- only kubelet/health-probe sources and the monitoring namespace or equivalent may connect to the
  management port;
- security-group, firewall, or Kubernetes NetworkPolicy rules enforce that boundary, with
  deny-by-default ingress and an illustrative policy;
- liveness and readiness expose status only; health details and components are never public;
- Prometheus access is management-network-only; info is no longer exposed;
- network isolation is required even though a dedicated management SecurityFilterChain exists; and
- deployments fail review if the two ports are accidentally mapped to the same public service.

Retain and make explicit the least-privilege Kafka ACL matrix:

| Principal | Required authority | Explicitly unnecessary |
| --- | --- | --- |
| Outbox publisher | Write and Describe on the main payment-captured topic | Read main/DLT, Write DLT |
| Notification consumer | Read and Describe main topic plus its consumer group | Write main, Read DLT |
| Main-listener DLT recoverer | Write and Describe DLT topic | Read DLT, arbitrary-topic write |
| DLT catalog consumer | Read and Describe DLT topic plus its consumer group | Write main or DLT |
| Replay publisher | Write and Describe main topic only | Read main, Write/Read DLT, arbitrary-topic write |

If one deployable process temporarily shares a Kafka credential, its ACL is the union of enabled
adapters only. Disabled adapters do not justify retained privileges. Topic creation, cluster admin,
offset mutation, and wildcard-topic privileges are never required at runtime.

### Backward-compatibility risks

| Change | Compatibility risk | Required handling |
| --- | --- | --- |
| Separate management port | Existing probes and Prometheus scrapes that still use the application port fail. Removing info may affect an undocumented scrape. | Update deployment targets atomically, verify the management-only path, and retain network isolation during rollback. |
| Semantic notification identity | A re-enveloped record that previously created another row now becomes a no-op or integrity failure. Existing semantic duplicates can block migration. | Treat this as the intended security correction; run preflight and reconcile existing conflicts explicitly without deletion. |
| Terminal DLT evidence | Operators must inspect a second immutable evidence table for non-replayable malformed DLT intake. | Update runbook queries and alerts; never synthesize original coordinates. |
| Time-bound idempotency | FR-008 and clients may assume indefinite replay. After retention, an old key can execute as new. | Change OpenAPI and product documentation before cleanup, announce the seven-day support window, and stage cleanup for a full window. |
| Customer quota | A customer at or above 10,000 orders receives a new 422 result. | Backfill exact usage, expose metrics, and require an approved quota decision rather than silently raising it. |
| Bounded lock wait | A same-key contender can receive 503 instead of waiting until the winner completes. | Return Retry-After and preserve same-key retry instructions. |
| Authenticated replay API | Existing callers of the actor-argument Java API and three-argument replay script break. Local Keycloak does not re-import an existing realm automatically. | Remove the direct command deliberately, update automation to bearer-authenticated HTTP, and document a local realm reset for the new scope. |
| Replay attempt caps | Records with legacy replay_count at or above the normal maximum can no longer use ordinary replay. | Require break-glass approval or leave replay disabled; never reset historical counts. |

## Finding-specific remediation

### Finding 1: unauthenticated Actuator health resource exhaustion

#### Threat addressed

An unauthenticated caller can repeatedly invoke readiness on the same public port as the API. Each
request can consume a database connection and, when Kafka is enabled, allocate a new Kafka Admin
client with threads, connections, and metadata work. A burst can consume local and dependency
resources even though the response contains no secret.

#### Proposed implementation

- Configure a separate management port and do not add additional health aliases to the application
  port.
- Remove Actuator permit rules from OrderSecurityConfiguration. Add a dedicated management-context
  chain using Spring Boot 4.1's supported Actuator endpoint matchers. Permit only status-only
  liveness/readiness needed by the protected deployment network. Keep Prometheus on that network and
  deny every other management request.
- Set endpoint access to opt-in/read-only, expose health and prometheus only, remove info, and set
  health show-details and show-components to never.
- Create one managed Kafka Admin from the existing KafkaAdmin configuration and close it on
  application shutdown. DependencyProbe reuses it for cluster and topic checks.
- Add a small thread-safe cache that stores the complete readiness result, including failure, for
  two seconds and shares one in-flight computation among concurrent callers. The cache has no
  unbounded keys. Startup validation bypasses it.
- Keep liveness free of PostgreSQL, Kafka, and provider probes. Readiness retains the current
  dependency semantics.

#### Acceptance criteria

- The application port does not serve any /actuator path, authenticated or unauthenticated.
- The management port differs from the application port and is configurable.
- Unauthenticated management-port liveness/readiness responses contain only status and no component
  names, cluster ID, exception, host, credential, or circuit details.
- Aggregate health, info, and every unapproved Actuator operation is denied or unavailable.
- One managed Kafka Admin exists for the application lifecycle; repeated health requests do not
  create Admin instances.
- At least 100 concurrent readiness requests within one cache interval cause at most one database
  probe and one Kafka probe and all complete within a bounded test deadline.
- A cached failure expires and a later healthy probe recovers without restart.
- Deployment documentation makes management-port network isolation mandatory and testable.

#### Tests

- Unit-test OperationsProperties bounds and success/failure cache expiry and in-flight coalescing
  with a controllable clock and latches.
- Unit-test that OperationalHealthIndicator returns status without sensitive details.
- Add a web integration test with random, distinct application and management ports. Assert the
  application port rejects /actuator/health/readiness, the management probe is status-only, and
  aggregate/info endpoints are unavailable.
- Inject counting database and Kafka probes and issue concurrent readiness requests; assert a single
  dependency execution.
- Assert the managed Admin is closed once during context shutdown and never per request.
- Add a static documentation assertion or deployment checklist test that the application service
  does not target LEDGERFLOW_MANAGEMENT_PORT.

#### Operational configuration

- LEDGERFLOW_MANAGEMENT_PORT and LEDGERFLOW_HEALTH_PROBE_CACHE_TTL as defined above.
- Probe clients use short connection/read timeouts and deployment-side rate limits. Cache TTL is not
  a substitute for network isolation.
- Alert on sustained readiness DOWN and on slow health-indicator warnings, not on one transient
  sample.

#### Migration or rollback considerations

- Database migration: None.
- Application rollback to the old single-port configuration is prohibited while the service has
  public ingress. The network policy and split Service configuration remain in place during a
  forward fix.
- A failed managed Kafka Admin startup fails dependency validation when Kafka is required; it must
  not silently restore per-request client creation.

### Finding 2: re-enveloped Kafka events duplicate notification side effects

#### Threat addressed

Event ID is a transport identity, not a complete business-effect identity. A compromised or
misconfigured writer with main-topic authority can copy valid capture data into a fresh envelope and
event ID. Current inbox and notification uniqueness accept it as new and create another notification
record.

#### Proposed implementation

- Keep event_id as notification_inbox primary key and keep the current canonical payload-hash
  conflict check for transport redelivery.
- After event validation, derive the v1 semantic effect described under Java interfaces. The key is
  ledgerTransactionId, not merely event type and payment ID; this supports legitimate future
  distinct capture ledger effects while preventing a new envelope for the same immutable capture
  journal.
- Persist effect identity and compared semantic content on notifications. Enforce uniqueness in
  PostgreSQL.
- In one transaction, claim the transport inbox row, attempt the semantic notification insert, and
  record APPLIED or SEMANTIC_DUPLICATE. A matching existing effect is a successful no-op. Conflicting
  content for the same effect identity rolls back the new inbox claim and raises an integrity error
  for DLT handling.
- Emit low-cardinality counters for applied effects, transport duplicates, semantic duplicates, and
  semantic conflicts. IDs are never metric labels.
- Document transport idempotency versus business-side-effect idempotency and the Kafka ACL matrix.

#### Acceptance criteria

- Re-delivery of the same event ID and content remains a no-op.
- The same event ID with different canonical content remains a non-replayable integrity failure.
- A different event ID with the same semantic identity and same compared content creates a second
  inbox row marked SEMANTIC_DUPLICATE and no second notification.
- A different event ID with the same semantic identity and conflicting order, payment, causation,
  amount, currency, or captured-at value creates no second notification and reaches DLT as a
  non-replayable integrity failure.
- Two concurrent differently enveloped records for the same semantic effect converge on exactly one
  notification through the database unique constraint.
- Two records with the same payment but distinct legitimate ledger transaction identities do not
  collide at the semantic-identity layer. Their acceptance as a future event type still requires
  that future contract's own domain validation.
- Documentation never claims end-to-end exactly-once delivery.

#### Tests

- Unit-test effect derivation, excluded transport fields, and each conflicting semantic field.
- PostgreSQL repository tests for the semantic unique constraint, matching no-op, conflicting
  content, and concurrent inserts.
- Kafka Testcontainers tests for a new event ID with matching semantic content, a new event ID with
  conflicting content, and concurrent re-enveloping.
- Retain all existing same-event-ID, retry, DLT, inbox, and notification assertions.
- Add a migration compatibility test that seeds valid V005-era data, upgrades through V006, and
  verifies backfill. Add failure fixtures for unmappable and duplicate semantic data; the migration
  must fail without deleting rows.

#### Operational configuration

- No new runtime knob changes semantic identity.
- Alert on any semantic conflict. Track semantic-duplicate rate and main-topic producer principal in
  broker audit logs.
- Provision only the documented topic ACLs. In particular, customer/API credentials and the DLT
  catalog principal cannot write the main topic.

#### Migration or rollback considerations

- Migration: V006 as specified above.
- Backfill is data-preserving and fail-closed. Existing duplicates require separately approved
  reconciliation; this plan does not delete or rewrite them.
- Once the unique semantic constraint is active, rolling back to the old consumer is unsafe because
  it does not handle semantic conflicts deliberately. Disable the notification consumer and roll
  forward instead.

### Finding 3: malformed DLT records permanently block a partition

#### Threat addressed

A record written directly to the DLT, or a corrupted record, can omit or malform Spring's original
routing headers. Current parsing throws before durable cataloging. Bounded retries repeatedly reset
after the recovery callback throws, the record stays uncommitted, and later records on the partition
starve.

#### Proposed implementation

- Treat missing, repeated, malformed, negative, oversized, or unexpected original-routing metadata;
  invalid payload size; and invalid DLT input structure as terminal input classifications.
- For terminal input, hash and measure the key and payload without storing their raw content, retain
  only allowlisted valid headers, and insert terminal_dlt_records using record.topic,
  record.partition, and record.offset as the authoritative coordinates.
- Return normally only after the transaction commits or verifies an identical existing coordinate.
  Record acknowledgement then advances the DLT offset.
- If the evidence insert fails because PostgreSQL is unavailable, propagate the dependency failure.
  The record remains uncommitted until evidence is durable. This temporary dependency pause is
  intentional and distinct from a poison record loop.
- Keep valid DLT records in dead_letter_records with original coordinates and replayability rules.
  terminal_dlt_records are never replayable.
- Add counters for terminal malformed DLT records, idempotent terminal redeliveries, and evidence
  persistence failures. Document alerts on the terminal counter and DLT consumer lag.

#### Acceptance criteria

- Missing original topic, partition, or offset; wrong byte widths; invalid/negative coordinates;
  unsupported original topic; invalid payload; and oversized payload each create one immutable
  sanitized terminal row keyed by actual DLT coordinates.
- No terminal row stores raw key, body, invalid routing value, exception message, or stack trace.
- The DLT offset commits only after terminal evidence is durable.
- Re-delivery of the same actual DLT coordinate verifies existing evidence and does not duplicate it.
- A valid record later on the same partition is consumed after a malformed record; the poison record
  does not starve the partition.
- A PostgreSQL failure before evidence commit leaves the DLT offset uncommitted. After database
  recovery, exactly one terminal row is written and later records proceed.
- A metric and documented alert distinguish terminal poison intake from catalog database outage.

#### Tests

- Unit-test safe terminal classification for every invalid original-header shape and input-size
  boundary.
- Kafka Testcontainers: publish directly to the DLT without original headers, followed by a valid
  DLT record on the same partition. Assert actual DLT coordinates, one terminal row, committed
  progress, and later cataloging.
- Repeat with malformed partition/offset bytes, unsupported original topic, null/invalid body, and
  payload at and over the bound.
- Use the existing PostgreSQL Toxiproxy support to interrupt terminal-evidence persistence. Assert no
  commit while unavailable and recovery without duplicates.
- Direct SQL update/delete tests prove terminal evidence is immutable.

#### Operational configuration

- No quarantine topic is introduced.
- Add Prometheus alerts for any increase in terminal malformed DLT records, sustained DLT catalog
  failures, and DLT consumer lag beyond the agreed threshold.
- Terminal metrics have stable failure-code tags only. Topic, partition, offset, event ID, and
  payload hash stay out of metric labels.

#### Migration or rollback considerations

- Migration: V007 as specified above.
- The table and trigger are additive. An old application ignores them but reintroduces the poison
  loop, so disable the DLT listener during rollback and deploy a forward fix.
- Terminal evidence is immutable and not removed during rollback.

### Finding 4: orders and idempotency records grow without quota or retention

#### Threat addressed

An authenticated subject can continually use fresh valid keys. Per-instance request limiting slows
one process but does not provide a durable multi-instance budget and resets on restart. Every
accepted request permanently adds an order and response snapshot. This permits storage exhaustion
and increasing index/backup/maintenance cost.

#### Proposed implementation

- Define a seven-day supported retry window in OpenAPI, API design, product requirements, ADR, and
  README. Keep completed records eight days by default. Reject configuration where retention is
  shorter than the supported window plus one cleanup interval.
- Add customer_order_usage and atomically increment it in the same transaction that owns a new
  idempotency claim and inserts an order. A matching replay checks the existing idempotency result
  before quota consumption and always replays even at quota.
- Enforce a default all-time maximum of 10,000 orders per authenticated subject. Persist order_limit
  with order_count so every instance observes the same database-authoritative quota. New subjects
  receive the configured default; changing the deployment default does not silently rewrite
  existing subjects. Increasing a subject's quota is not exposed by this plan and requires an
  approved operational change.
- Set a transaction-local PostgreSQL lock_timeout before idempotency claim and quota acquisition.
  Map SQLSTATE 55P03 to the existing retryable idempotency-unavailable response rather than waiting
  without bound. Do not retry inside the server.
- Add an orders-owned scheduled cleanup worker. Each transaction selects only COMPLETED
  idempotency rows older than retention in a bounded batch using SKIP LOCKED, then deletes those
  rows. It never deletes IN_PROGRESS state, orders, or a record within the supported window.
- Make the cleanup worker multi-instance safe and give it a dedicated single-thread scheduler and
  bounded shutdown.
- Add counters for orders created, quota rejections, lock-timeout rejections, and idempotency rows
  deleted. Add cached gauges or scheduled observations for total/oldest idempotency rows and
  customer usage near quota without querying per request or using customer IDs as labels.

#### Acceptance criteria

- A customer can create at most the configured number of new orders across concurrent application
  instances. Exactly one request consumes each unit.
- A replay of a prior order succeeds when the customer is at quota and does not increment usage.
- The first new order above quota returns documented 422 and creates neither order nor idempotency
  row.
- Same-key concurrency still creates one order. A deliberately held conflicting claim produces
  bounded 503 for the waiter within the configured lock timeout; a later same-key retry replays the
  winner.
- No completed idempotency record is deleted before the full seven-day supported window.
- Cleanup deletes only eligible completed rows in bounded transactions and concurrent workers do
  not double-count or block active rows.
- After retention expires, reuse of an old key is documented as a new request and can create a new
  order only if quota permits. The contract does not imply indefinite deduplication.
- Growth metrics and runbook queries expose total orders, per-customer usage, idempotency age, cleanup
  throughput, quota rejection, and cleanup failure without high-cardinality labels.

#### Tests

- Configuration unit tests for retry-window, retention, interval, batch, quota, and lock-timeout
  bounds.
- OrderService unit tests that replays precede quota consumption and quota failure rolls back the
  idempotency claim.
- PostgreSQL integration tests for usage backfill, atomic increment, quota boundary, concurrent
  different-key requests at the final quota unit, and transaction rollback.
- PostgreSQL concurrency test that holds the same key and proves bounded lock timeout, no duplicate
  order, and successful later replay.
- Cleanup tests with a fixed clock for before-window, exact-boundary, after-retention, in-progress,
  locked-row, concurrent-worker, and batch-size cases.
- HTTP tests for 422 order_quota_exceeded, 503 Retry-After, replay at quota, and updated OpenAPI
  examples.
- Migration test from V001-era populated data through V008, verifying usage count and cleanup index.

#### Operational configuration

- Use the retry-window, retention, cleanup, quota, and lock-timeout variables defined above.
- Cleanup starts disabled for the first production deployment, runs in report-only/preflight mode
  for one complete retry window, then is enabled through reviewed deployment configuration after
  counts and backup coverage are confirmed. The completed milestone requires enabled cleanup in the
  production configuration template.
- Alert on quota-rejection spikes, cleanup failures, oldest eligible idempotency age beyond
  retention plus two intervals, and database size forecasts.

#### Migration or rollback considerations

- Migration: V008 as specified above.
- Quota migration backfills exact counts and does not delete existing data. If an existing subject
  already exceeds the default, its usage is retained and new creates are rejected until an approved
  quota decision; migration does not hide or reduce the count.
- Retention deletion is irreversible without a backup. Stop cleanup before application rollback.
  Deleted response snapshots cannot be reconstructed from mutable current state and must not be
  fabricated.
- After any deletion, rollback cannot restore the old indefinite-idempotency promise. Keep the
  time-bound API contract and roll forward.

### Finding 5: replay identity is caller-asserted and attempts are unbounded

#### Threat addressed

Anyone able to invoke the current command can claim another actor string. A valid replayable record
can be retried immediately and indefinitely after broker failure. This weakens attribution, enables
resource abuse, and can amplify duplicate publication.

#### Proposed implementation

- Define the three operator operations in OpenAPI before implementation. Keep inspection and replay
  scopes separate and require an allowlisted role in addition to scope.
- Derive actor issuer and subject from the validated JwtAuthenticationToken. Record authorized
  client ID when present. Do not accept actor in path, query, body, header, environment, or script
  argument.
- Return only a sanitized dead-letter inspection projection. Never return validated_payload,
  safe_headers wholesale, payload hash, raw body, stack trace, SQL text, or secrets.
- Require Idempotency-Key for each replay command. Persist scoped key hash, canonical request hash,
  command lease, and stable response. Same command replays its result; changed content returns 409.
- Allow three normal attempts per dead-letter record. After a failed publish, set
  replay_available_at to now plus five minutes. A new command during cooldown is rejected without
  publishing.
- After three normal attempts, only the separate break-glass endpoint may request one additional
  attempt. It requires admin role, ledgerflow.operations.break-glass, a 10 to 100 character
  approval reference, and immutable audit. One approval reference can authorize only one attempt.
  At most two break-glass attempts are allowed, producing an absolute default cap of five.
- Publish outside the database transaction under the existing lease. Broker acknowledgement still
  precedes PUBLISHED. A crash after publish may cause duplicate publication on takeover; the
  transport and semantic notification controls make that safe without claiming exactly once.
- Append immutable audit for request, publish, failure, cooldown/limit rejection, and break-glass
  use. Audit identity_source distinguishes legacy caller-asserted history from new OIDC identity.
- Remove the non-web boot command and change scripts/replay-dead-letter to call the authenticated
  API with a token from the environment. Document token handling and prohibit shell tracing.
- Add ledgerflow.operations.break-glass to the local Keycloak realm as an optional scope. Do not
  create users, passwords, clients with secrets, or default admin grants.

#### Acceptance criteria

- A customer, unauthenticated caller, operator with read only, operator without retry scope, and
  non-admin break-glass caller cannot publish.
- Read-only inspection succeeds with operations.read but cannot invoke either replay path.
- Audit issuer, subject, and client identity match the validated JWT and cannot be overridden by any
  request field.
- The same replay command key and request returns the original command/result without another
  attempt. Changed request content returns 409.
- Failed attempts cannot run again before cooldown. Exactly three normal attempts are possible.
- Attempt four requires the break-glass path, admin role, break-glass scope, and an unused approval
  reference. The sixth total attempt is rejected at the default absolute cap.
- Concurrent normal or break-glass commands cannot exceed the limits or publish twice for one
  command key.
- Audit update/delete still fails. Legacy rows remain identifiable and unchanged.
- The replay script has no actor parameter and no direct bootRun path. A token never appears in
  logs, process arguments, traces, problem details, or audit reason.
- Kafka destination, key, event ID, and canonical payload always come from the validated catalog,
  never the request.

#### Tests

- OpenAPI validation and examples for inspection, normal replay, break glass, problem responses, and
  idempotency.
- HTTP negative authorization matrix covering missing token, customer role, read-only operator,
  retry scope without role, operator break-glass attempt, and admin missing break-glass scope.
- HTTP tests that actor-like request properties are rejected as unknown and cannot alter audit.
- PostgreSQL tests for command idempotency, request-hash conflict, cooldown boundary, concurrent
  claims, normal maximum, one-use approval reference, absolute cap, expired lease takeover, stale
  owner rejection, and immutable audit.
- Kafka Testcontainers tests for acknowledged replay, failed publication and cooldown, crash after
  publish before local acknowledgement, and semantic no-duplicate behavior after takeover.
- Script test or shell static check for required token environment, no actor argument, no shell
  tracing, and safe status handling.
- Migration compatibility test preserving and labeling existing caller-asserted audit rows.

#### Operational configuration

- Use replay maximum, cooldown, break-glass maximum, and lease variables defined above.
- The application port operator routes require internal/operator ingress controls in addition to
  JWT. Apply bounded edge request rates to inspection and stricter rates to replay.
- Production replay uses a Kafka principal limited to Write/Describe on the one main topic. Revoke
  that credential when replay is disabled.
- Break-glass approval references come from an external incident/change record; LedgerFlow stores
  the bounded reference, not the ticket body.

#### Migration or rollback considerations

- Migration: V009 as specified above.
- Legacy audit rows remain append-only and are marked LEGACY_CALLER_ASSERTED; they are not rewritten
  to imply authenticated attribution.
- The old replay boot command must not run after V009. During application rollback, disable replay,
  revoke the replay producer ACL, and keep inspection read-only until a forward fix is deployed.
- Broker-acknowledged duplicate publication after a crash remains expected at-least-once behavior;
  do not repair it by editing replay, inbox, notification, or audit rows.

## Expected implementation touch set

The following is the anticipated maximum touch set. An approved milestone may use fewer files.
Adding unrelated files or expanding to other modules requires a plan update and maintainer review.

### Shared configuration, contracts, and deployment documentation

- application/src/main/resources/application.yaml
- application/src/main/openapi/ledgerflow.yaml
- application/src/integrationTest/resources/application-integration-test.yaml
- .env.example
- README.md
- docs/architecture.md
- docs/api-design.md
- docs/data-model.md
- docs/product-requirements.md
- docs/threat-model.md
- docs/runbook.md
- docs/deployment-security.md
- docs/adr/0008-secured-operator-recovery.md
- docs/adr/0010-isolate-management-endpoints.md
- docs/adr/0011-separate-transport-and-semantic-notification-idempotency.md
- docs/adr/0012-terminally-catalog-malformed-dlt-input.md
- docs/adr/0013-bound-order-and-idempotency-growth.md

ADR 0008 is still Proposed, so the replay design is revised there before it is accepted. New ADRs
record changes to already accepted health, Kafka, and idempotency decisions rather than silently
rewriting their historical rationale.

### Management isolation

- modules/orders/src/main/java/com/ledgerflow/orders/internal/web/OrderSecurityConfiguration.java
- modules/operations/src/main/java/com/ledgerflow/operations/internal/DependencyProbe.java
- modules/operations/src/main/java/com/ledgerflow/operations/internal/OperationalHealthIndicator.java
- modules/operations/src/main/java/com/ledgerflow/operations/internal/OperationsConfiguration.java
- modules/operations/src/main/java/com/ledgerflow/operations/internal/OperationsProperties.java
- one new operations-owned management security configuration using Spring Boot's management-context
  mechanism
- modules/operations/src/test/java/com/ledgerflow/operations/internal/OperationalHealthIndicatorTest.java
- focused new operations unit tests
- one new application management-port integration test

### Notification semantic identity and DLT intake

- application/src/main/resources/db/migration/V006__add_notification_semantic_effect_identity.sql
- application/src/main/resources/db/migration/V007__record_terminal_dlt_evidence.sql
- modules/notifications/src/main/java/com/ledgerflow/notifications/internal/kafka/NotificationEventValidator.java
- modules/notifications/src/main/java/com/ledgerflow/notifications/internal/kafka/ValidatedNotificationEvent.java
- modules/notifications/src/main/java/com/ledgerflow/notifications/internal/kafka/DeadLetterCatalogListener.java
- modules/notifications/src/main/java/com/ledgerflow/notifications/internal/kafka/NotificationsConfiguration.java
- modules/notifications/src/main/java/com/ledgerflow/notifications/internal/persistence/JdbcNotificationStore.java
- modules/notifications/src/main/java/com/ledgerflow/notifications/internal/persistence/DeadLetterCatalogEntry.java
- narrowly scoped new semantic-effect and terminal-evidence value types
- modules/notifications/src/test/java/com/ledgerflow/notifications/internal/kafka/NotificationEventValidatorTest.java
- focused new notification unit tests
- application/src/integrationTest/java/com/ledgerflow/notifications/KafkaRetryAndDltIntegrationTest.java

### Order quota and idempotency retention

- application/src/main/resources/db/migration/V008__bound_order_and_idempotency_growth.sql
- modules/orders/src/main/java/com/ledgerflow/orders/internal/application/OrderService.java
- modules/orders/src/main/java/com/ledgerflow/orders/internal/application/OrderStore.java
- modules/orders/src/main/java/com/ledgerflow/orders/internal/persistence/JdbcOrderStore.java
- modules/orders/src/main/java/com/ledgerflow/orders/internal/web/OrderProblemHandler.java
- narrowly scoped new orders-owned properties, quota exception, retention worker, and scheduler
- focused new orders unit tests
- application/src/integrationTest/java/com/ledgerflow/orders/OrderIdempotencyIntegrationTest.java
- application/src/integrationTest/java/com/ledgerflow/orders/OrderRepositoryIntegrationTest.java
- application/src/integrationTest/java/com/ledgerflow/orders/OrderHttpIntegrationTest.java

### Authenticated replay

- application/src/main/resources/db/migration/V009__authenticate_and_bound_dlt_replay.sql
- application/src/main/java/com/ledgerflow/ReplayCommandConfiguration.java, removed
- scripts/replay-dead-letter
- infra/keycloak/ledgerflow-realm.json
- modules/notifications/build.gradle.kts
- modules/notifications/src/main/java/com/ledgerflow/notifications/api/DeadLetterReplay.java
- modules/notifications/src/main/java/com/ledgerflow/notifications/internal/application/DeadLetterReplayService.java
- modules/notifications/src/main/java/com/ledgerflow/notifications/internal/application/NotificationsProperties.java
- modules/notifications/src/main/java/com/ledgerflow/notifications/internal/persistence/JdbcNotificationStore.java
- modules/notifications/src/main/java/com/ledgerflow/notifications/internal/persistence/ReplayClaim.java
- narrowly scoped new notifications-owned operator web, command-idempotency, sanitized projection,
  and trusted-actor types
- modules/orders/src/main/java/com/ledgerflow/orders/internal/web/OrderSecurityConfiguration.java
- one new operator replay HTTP integration test, using PostgreSQL and Kafka Testcontainers

### Shared integration support

- application/src/integrationTest/java/com/ledgerflow/testing/PostgreSqlIntegrationTest.java
- application/src/integrationTest/java/com/ledgerflow/testing/KafkaIntegrationTest.java
- one new migration compatibility integration test

No Gradle version, production dependency version, application module, Compose service, business
state, or current migration is expected to change.

## Supporting implementation slices

The canonical mappings are:

- R1 and R2 together map to approved canonical Milestone 5D, executed R1 then R2;
- R3 remains a separately proposed quota/retention follow-up;
- R4 remains a separately proposed authenticated/bounded replay follow-up and a gate for canonical
  Milestone 7B; and
- R5 is not a separately approvable milestone. The canonical milestone being completed owns its full
  `./gradlew clean verify` gate.

Only `docs/plans/mvp-execplan.md` may change those statuses or ordering.

### Slice R1 — Isolate and bound management health

- Canonical status: Included in approved Milestone 5D; not independently executable
- Intended outcome: Health and metrics are served only on a separate protected management port, and
  concurrent readiness requests share bounded dependency work.
- Implementation work:
  - Implement Finding 1 exactly as specified.
  - Add ADR 0010 and the management portions of deployment-security, architecture, threat model,
    runbook, README, and environment example.
  - Do not touch Kafka notification, order persistence, or replay behavior.
- Validation commands:

      ./gradlew :modules:operations:test
      ./gradlew :application:integrationTest --tests '*Management*'
      ./gradlew architectureTest documentationCheck spotlessCheck
      git diff --check

- Observable acceptance:
  - Every Finding 1 acceptance criterion passes.
  - The application port serves no Actuator endpoint.
  - Counting tests prove one managed Admin and one coalesced dependency probe per interval.

### Slice R2 — Enforce notification semantic idempotency and terminal DLT progress

- Canonical status: Included in approved Milestone 5D; not independently executable
- Intended outcome: Re-enveloping cannot duplicate a notification, and malformed DLT input cannot
  starve its partition after durable terminal evidence is written.
- Implementation work:
  - Apply V006 and V007.
  - Implement Findings 2 and 3 exactly as specified.
  - Add ADRs 0011 and 0012 and the Kafka ACL/operational documentation.
  - Do not add event types, notification delivery, a quarantine topic, or replay HTTP.
- Validation commands:

      ./gradlew :modules:notifications:test
      ./gradlew :application:integrationTest --tests '*KafkaRetryAndDltIntegrationTest'
      ./gradlew :application:integrationTest --tests '*MigrationCompatibilityIntegrationTest'
      ./gradlew architectureTest documentationCheck spotlessCheck
      git diff --check

- Observable acceptance:
  - Every Finding 2 and Finding 3 acceptance criterion passes.
  - Kafka tests prove concurrent semantic deduplication, conflict DLT, malformed-DLT progress, and
    database-outage recovery.

### Follow-up R3 — Bound order storage and retain idempotency safely

- Canonical status: Proposed follow-up; requires separate maintainer approval
- Intended outcome: New orders are subject to an authoritative customer storage quota, lock waits
  are bounded, and completed idempotency snapshots expire only after the documented retry window.
- Implementation work:
  - Apply V008.
  - Implement Finding 4 exactly as specified.
  - Add ADR 0013 and update the order OpenAPI contract before or with implementation.
  - Do not delete orders or define retention for any other table.
- Validation commands:

      ./gradlew :modules:orders:test
      ./gradlew :application:integrationTest --tests '*Order*'
      ./gradlew :application:integrationTest --tests '*MigrationCompatibilityIntegrationTest'
      ./gradlew openApiValidate architectureTest documentationCheck spotlessCheck
      git diff --check

- Observable acceptance:
  - Every Finding 4 acceptance criterion passes.
  - The quota, same-key lock timeout, cleanup batch, and replay-at-quota tests pass against
    PostgreSQL.

### Follow-up R4 — Authenticate, authorize, and bound DLT replay

- Canonical status: Proposed follow-up; requires separate maintainer approval
- Intended outcome: Operators inspect and replay through contract-first JWT-secured routes; actor
  identity cannot be asserted by the caller, and cooldown, normal maximum, break glass, command
  idempotency, and immutable audit are enforced.
- Implementation work:
  - Apply V009.
  - Implement Finding 5 exactly as specified.
  - Revise and accept ADR 0008 only if the maintainer's milestone approval explicitly accepts that
    ADR; otherwise keep the ADR Proposed and do not implement.
  - Do not implement payment/outbox recovery or the general operations API.
- Validation commands:

      ./gradlew :modules:notifications:test
      ./gradlew :application:integrationTest --tests '*OperatorReplay*'
      ./gradlew :application:integrationTest --tests '*KafkaRetryAndDltIntegrationTest'
      ./gradlew :application:integrationTest --tests '*MigrationCompatibilityIntegrationTest'
      ./gradlew openApiValidate architectureTest documentationCheck spotlessCheck
      git diff --check

- Observable acceptance:
  - Every Finding 5 acceptance criterion passes.
  - Negative authorization, identity derivation, idempotency, cooldown, cap, break-glass, crash
    window, and immutable audit tests pass.

### Validation design R5 — Run the complete abuse-case and quality gate

- Canonical status: Not separately approvable; validation belongs to the active canonical milestone
- Intended outcome: All five remediations work together and every repository completion check
  passes with accurate operational documentation.
- Implementation work:
  - Run the full test matrix without adding features or cleanup.
  - Exercise the documented management-port and Kafka ACL deployment checklist.
  - Reconcile every acceptance criterion in this plan and record evidence in Progress.
- Validation commands:

      docker info
      ./gradlew --no-daemon clean verify --console=plain
      scripts/security-scan
      git diff --check
      git status --short

- Observable acceptance:
  - clean verify passes formatting, static analysis, unit, PostgreSQL/Kafka/Toxiproxy integration,
    architecture, OpenAPI, Compose, and documentation checks;
  - the abuse regression suite passes all five finding matrices;
  - the security scan has no unapproved new finding caused by this work; and
  - documentation describes actual behavior, residual at-least-once delivery, management network
    trust, time-bound idempotency, quotas, DLT terminal evidence, and replay break glass.

## Implementation approach

1. Before a canonical milestone uses one of these slices, re-read both documents, verify the
   canonical milestone alone is explicitly approved, inspect git status, and update only the
   canonical Metadata and Progress. Do not infer approval from this record.
2. Write the ADR and, where applicable, OpenAPI contract before or with the first implementation
   change. Do not broaden the API beyond the three dead-letter operations and one order-quota error.
3. Add each Flyway migration once and validate it both from an empty database and from representative
   V005 data. Never edit V001 through V005.
4. Make PostgreSQL the authority for semantic effect uniqueness, customer usage, replay command
   idempotency, cooldown, and attempt caps. In-memory state may only coalesce health probes.
5. Keep each external call outside PostgreSQL transactions. Kafka replay remains at least once.
6. Use low-cardinality metrics and sanitized bounded errors. IDs, subjects, coordinates, hashes,
   reasons, approval references, and topic names are not metric labels.
7. Update focused tests alongside each control, then run that milestone's commands. Fix failures
   within scope; do not weaken a check.
8. The active canonical milestone runs and records its complete gate; R5 is only the original
   combined validation design. A scan finding requires remediation or explicit maintainer
   disposition under existing governance; this record grants neither suppression nor risk
   acceptance.

### Why these are the smallest safe changes

- A separate port without network policy is not isolation, and network policy without removing
  application-port Actuator rules leaves an unsafe default. Both are necessary; a second telemetry
  system is not.
- Reusing one Kafka Admin and coalescing one readiness result removes resource amplification without
  changing readiness meaning or adding a cache dependency.
- Ledger transaction identity is already carried in the event and represents the immutable capture
  accounting effect. It avoids a new event field and avoids treating payment ID alone as forever
  sufficient.
- A dedicated terminal DLT evidence table avoids inventing fake original coordinates or weakening
  the existing replayable dead-letter constraints.
- A per-customer all-time order counter bounds durable business growth across instances without
  adding Redis or deleting business records. Idempotency cleanup is independently bounded by its
  public retry contract.
- A narrow dead-letter HTTP adapter replaces unverifiable actor text using the resource server and
  scopes already present. It does not implement the broader future operations subsystem.

## Validation and acceptance

### Required environment

- Java 25 selected by the Gradle toolchain.
- The committed Gradle 9.6.1 Wrapper; never system Gradle.
- Docker with PostgreSQL 18, Kafka 4.3, and Toxiproxy Testcontainers available.
- No real secret, bearer token, or production credential in source, fixtures, command history, or
  test output.

### Focused test matrix

| Abuse case | Required proof |
| --- | --- |
| Health flood | Distinct ports, no application-port Actuator, status-only response, one in-flight dependency probe, managed Admin reuse, cache recovery |
| Re-envelope | Same event transport no-op, new event matching semantic no-op, semantic content conflict DLT, concurrent one notification |
| DLT poison | Missing/malformed headers persist actual DLT coordinates, offset advances after commit, next record proceeds, DB outage does not lose evidence |
| Storage abuse | Database quota under concurrency, replay at quota, bounded lock wait, no early cleanup, bounded multi-worker cleanup, growth metrics |
| Replay abuse | JWT-derived identity, read/retry/break-glass separation, command idempotency, cooldown, normal and absolute caps, immutable audit, crash duplicate safe |

### Combined validation reference

If the maintainer eventually approves and completes all five remediations through canonical
milestones or follow-ups, the combined validation is complete only when:

- every acceptance criterion in all five finding sections has test or documented deployment evidence;
- V001 through V005 checksums are unchanged;
- V006 through V009 apply in order to an empty PostgreSQL Testcontainer;
- the upgrade fixture preserves legacy records or fails safely on documented integrity conflicts;
- the OpenAPI contract validates and HTTP tests conform;
- Spring Modulith and ArchUnit boundaries still pass;
- git diff --check passes;
- ./gradlew --no-daemon clean verify --console=plain passes; and
- scripts/security-scan has either passed or any pre-existing external-image blocker is recorded
  without being falsely attributed to or suppressed by this plan.

## Rollback and recovery

### General

- Stop the affected scheduler, consumer, or replay Kafka authority before rolling back application
  code. Do not edit a merged migration and do not manually mutate protected rows.
- Correct schema defects with a new forward Flyway migration.
- If a deployment partially enables a control, fail closed: management traffic stays isolated,
  notification/DLT consumers stay stopped when their schema or code is incompatible, new order
  creation stays disabled if quota state is uncertain, and replay publish authority stays revoked.

### Management

- Keep the management-only Service, firewall, and network policy in place during rollback. Never
  route the old single-port application publicly as a workaround.

### Semantic notifications and DLT

- Stop both notification listeners before application rollback below V006/V007 behavior. Preserve
  inbox, notification, dead-letter, and terminal evidence. Roll forward.
- A failed semantic backfill leaves the migration unapplied and data unchanged. Investigate and use
  a separately approved reconciliation migration; never delete a duplicate to make migration pass.

### Orders and idempotency

- Stop cleanup before rollback. Quota and usage structures are additive and remain.
- Deleted idempotency snapshots are not reconstructed. Maintain the time-bound public contract and
  restore only from an approved backup when incident recovery requires it.

### Replay

- Revoke replay producer Write authority and disable replay routes/worker before rollback. Keep
  read-only inspection only if its schema remains compatible.
- Never re-enable the caller-asserted boot command. Preserve all legacy and OIDC audit rows.

## Progress

- [x] 2026-07-14 09:41Z — Read the repository's engineering and planning conventions, the active
  MVP ExecPlan status and relevant progress/decisions, current git status, and the source,
  migrations, tests, configuration, contracts, ADRs, threat model, architecture, data model,
  product requirements, and runbook referenced by the five findings.
- [x] 2026-07-14 09:41Z — Verified all five reported behaviors still exist, identified their
  feature-specific production blockers, selected the smallest safe control boundaries, identified
  four additive migrations and compatibility risks, and defined regression and abuse-case tests.
- [x] 2026-07-14 09:41Z — Drafted this Proposed ExecPlan only. No application code, migration,
  contract, active plan, or business behavior was changed.
- [x] 2026-07-14 09:46Z — Ran git diff --check and
  ./gradlew --no-daemon spotlessCheck documentationCheck --console=plain; both passed. Consolidated
  assumptions and compatibility risks after self-review.
- [x] 2026-07-14 10:10Z — Ran the operations, notifications, and orders unit-test tasks together
  with Spotless and documentation checks; all 19 task actions passed or were up to date. Final git
  status contains only this untracked Proposed plan.
- [x] 2026-07-15 11:14Z — The maintainer made `docs/plans/mvp-execplan.md` the unambiguous source of
  truth, approved canonical Milestone 5D containing R1/R2, kept R3/R4 separately Proposed, and
  removed independent execution authority from this supporting record.
- [x] 2026-07-15 12:39Z — Recorded R1/R2 implementation and verification evidence only in canonical Milestone 5D progress; this supporting record remains non-authoritative.
- [ ] Revisit R3 only after separate maintainer approval recorded in the canonical plan.
- [ ] Revisit R4 only after separate maintainer approval recorded in the canonical plan.

## Surprises and discoveries

- The threat model states that Actuator is not exposed on the public API, but the only active
  SecurityFilterChain explicitly permits health and info and no separate management port exists.
- Health response details are sanitized, but the dominant risk is work amplification. DependencyProbe
  allocates a Kafka Admin for every Kafka readiness call.
- The existing Kafka test named malformedPoisonMessage exercises malformed input on the main topic.
  Spring's recoverer adds valid original-routing headers before DLT publication, so it does not cover
  malformed input arriving directly on the DLT.
- notifications.payment_id is indexed but not unique. Event-ID uniqueness therefore cannot prevent
  a differently enveloped semantic duplicate.
- replay_count and replay_available_at already exist, but current failure handling sets eligibility
  to now and claim SQL has no count predicate.
- Product requirement FR-008 explicitly promises indefinite idempotency retention. Remediation is a
  deliberate contract change, not merely a cleanup job.
- Milestone 5C subsequently completed. Canonical Milestone 5D now owns approved R1/R2 execution;
  keeping independent statuses here would recreate the planning ambiguity this amendment resolves.

## Decision log

- 2026-07-14 — Keep this plan Proposed and separate from docs/plans/mvp-execplan.md (superseded by
  the 2026-07-15 canonical-plan decision below). Rationale: the current plan then had an In Progress
  milestone and repository governance permits only one active milestone. ADR required: No.
- 2026-07-14 — Use one managed Kafka Admin plus a two-second coalesced readiness snapshot. Rationale:
  it removes per-request allocation and burst multiplication while preserving current dependency
  semantics with existing libraries. ADR required: ADR 0010.
- 2026-07-14 — Use ledger transaction ID as the v1 notification effect key and compare the complete
  effect-relevant capture content. Rationale: it is an immutable domain source identity, catches a
  new event envelope, and does not permanently assume one event per payment. ADR required: ADR 0011.
- 2026-07-14 — Store terminal malformed DLT evidence separately using actual DLT coordinates.
  Rationale: fake original coordinates would corrupt evidence and making current original fields
  nullable would weaken replayable catalog invariants. ADR required: ADR 0012.
- 2026-07-14 — Guarantee seven days of HTTP idempotency, retain eight days, and cap each subject at
  10,000 orders. Rationale: explicit product bounds are enforceable and testable; indefinite storage
  is not. These are proposed defaults selected only by milestone approval. ADR required: ADR 0013.
- 2026-07-14 — Replace direct caller-asserted replay with narrow JWT-authenticated dead-letter
  routes, three normal attempts, five-minute cooldown, and at most two one-approval break-glass
  attempts. Rationale: existing OAuth and catalog controls can enforce attribution and bounded work
  without a general operations subsystem. ADR required: revise proposed ADR 0008.
- 2026-07-14 — Add no production technology. Rationale: PostgreSQL, Spring Security, Kafka,
  Actuator, Micrometer, and JDK concurrency primitives already provide the required controls. ADR
  required: No.
- 2026-07-15 — Defer all execution status to `docs/plans/mvp-execplan.md`. Rationale: the repository
  already has one maintainer-designated canonical MVP ExecPlan, and two milestone authorities would
  drift. R1/R2 are incorporated into canonical Milestone 5D; R3/R4 stay separately proposed there.
  ADR required: No.

## Outcome and follow-up

Historical design outcome: all five findings were verified and this supporting record defined their
feature-specific deployment blockers, control boundaries, migrations, compatibility/rollback
constraints, operational configuration, expected file scope, and abuse regression tests. The
canonical plan now records Milestone 5D R1/R2 as implemented and Complete on 2026-07-15 with focused
and full verification evidence. R3/R4 remain separately Proposed; this supporting record still has
no independent execution authority.

When a canonical milestone completes, record delivered behavior, migration evidence, command
results, accepted ADRs, deviations, and residual risk in `docs/plans/mvp-execplan.md`; keep this
historical design outcome intact.

Separately proposed follow-up work is not approved by this plan. Examples include production cloud
IaC, a quarantine topic, retention for other event/audit tables, dynamic quota administration, a
general failed-operations API, payment/outbox operator retry, and event contracts for partial
capture.

## References

- Spring Boot Actuator endpoint access, security, health-detail, health-group, and management-port
  guidance: https://docs.spring.io/spring-boot/reference/actuator/endpoints.html
- Spring Boot management HTTP configuration:
  https://docs.spring.io/spring-boot/reference/actuator/monitoring.html
- Spring Kafka CommonErrorHandler acknowledgement contract:
  https://docs.spring.io/spring-kafka/docs/current/api/org/springframework/kafka/listener/CommonErrorHandler.html
- OWASP API4:2023 Unrestricted Resource Consumption:
  https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/
- Apache Kafka authorization and ACLs:
  https://kafka.apache.org/documentation/#security_authz
