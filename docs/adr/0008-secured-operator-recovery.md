# ADR 0008: Provide Secured, Audited, Idempotent Operator Recovery

- Status: Accepted
- Date: 2026-07-17
- Decision owners: LedgerFlow maintainers

## Context

Payment ambiguity, exhausted outbox publication, and dead-lettered notification events must be inspectable and recoverable without direct database edits, arbitrary Kafka commands, or application restarts. Recovery is privileged and can repeat financial or messaging work if invoked incorrectly.

The local stack imports roles and scopes into Keycloak. Production identity remains a deployment
dependency; unauthenticated or caller-asserted operator identity is not acceptable.

## Decision

LedgerFlow acts as an OAuth 2.0 JWT resource server. It validates RS256 signature only, exact configured issuer, `ledgerflow-api` audience, expiry, and not-before. Initial trusted JWKS loading gates readiness and validation fails closed without a valid cached key. The external identity provider is a deployment dependency, not part of the MVP.

Use separate scopes:

- `ledgerflow.orders.write` and `ledgerflow.orders.read` for customer operations;
- `ledgerflow.operations.read` for failed-operation inspection;
- `ledgerflow.operations.retry` for retry commands; and
- `ledgerflow.operations.break-glass` plus the `admin` role for exceptional approval evidence.

Operator routes live under `/api/v1/operator/operations` in the OpenAPI contract and must also be placed behind internal/management ingress controls in production.

The `operations` module builds bounded read-only projections from module-owned failure records and
dispatches only through narrow `OperationRecoveryHandler` interfaces implemented by orders,
messaging, and notifications. Public operation IDs include a server-owned type prefix so an ID from
one recovery domain cannot be confused with another. The API exposes stable codes, sanitized
attempt history, timestamps, and retryability, never raw provider/Kafka bodies, tokens, customer
subjects, stack traces, or secrets.

A retry request:

- is accepted only for a currently retryable operation;
- requires operator retry scope, `Idempotency-Key`, and a bounded audit reason;
- stores a hashed key and request fingerprint;
- permits only one active retry per failed operation;
- records the request and append-only audit in the acceptance transaction;
- returns `202 Accepted` plus a separate sanitized status resource; and
- selects the action and original provider/event identifiers server-side.

A leased worker claims accepted requests with `SELECT ... FOR UPDATE SKIP LOCKED`, a lease token,
owner, and monotonically increasing version. Expired work may be taken over; handlers recheck the
lease before external work, and each local mutation transaction locks and validates the command's
owner, token, version, and expiry before committing. An external call already in flight may finish
after expiry, so stable provider/event identities absorb that unavoidable duplicate boundary;
stale local mutation or completion is rejected and audited. Worker spans start independent traces
and link to the authenticated HTTP request and stored original failure context.

Payment recovery resumes the persisted stage with the original provider operation key and always
looks up that identity first. Confirmed lookup results are applied directly; only `NOT_FOUND`
permits one same-ID provider attempt for the operator command. Ledger/outbox/order finalization
remains idempotent. Outbox recovery releases the same failed row to `PENDING` for one new bounded
publisher cycle and waits for the normal publisher. DLT recovery republishes the validated stored
event ID, key, and canonical body while replacing retry-only transport metadata. Event-ID and
semantic-effect idempotency remain enforced.

Each failed command establishes a transactional cooldown. Three automatic operator attempts are
allowed by default. Further work requires immutable, separately authorized break-glass approval;
approval and execution create separate audit actions, an approval can be consumed once, and two
break-glass executions are allowed by default. These bounds are configurable within validated
limits. Database triggers reject updates/deletes of audit, approval, approval-use, and attempt
evidence and protect retry request identity.

The operations Gradle module declares Spring Web MVC, Validation, OAuth2 Resource Server, and the
OpenTelemetry API directly because its controller, authenticated identity derivation, request
constraints, and span-link worker compile against those types. These are already BOM-managed
platform capabilities used by the deployable application, so this adds no new runtime technology
or independently versioned library. The operational/security impact is the privileged HTTP surface
and telemetry described above; validation remains fail-closed and telemetry export cannot affect a
recovery result.

## Consequences

### Positive

- Operators recover through a constrained domain API rather than mutating infrastructure.
- Every privileged retry is attributable and replay-safe.
- Customer ownership and operator privileges have explicit tests.
- Server-controlled replay prevents arbitrary destination or payload injection.

### Costs and risks

- Deployment must supply an issuer/JWK configuration and protect internal ingress.
- Failure projections, DLT cataloging, retry dispatch, and audit add durable data.
- JWT scope design and key rotation require operational ownership.
- Rate limiting and DDoS protection still depend partly on the deployment edge.

## Alternatives considered

### No authentication in the portfolio MVP

Rejected because operator retry is a sensitive business flow and an unauthenticated design would teach an unsafe pattern.

### Direct database or Kafka administration

Rejected because it bypasses state validation, idempotency, authorization, and audit.

### Let operators submit replacement payloads or provider keys

Rejected because it enables tampering and duplicate effects. The server owns recovery inputs.

### Automatically replay DLT records forever

Rejected because poison messages can create an unbounded loop and conceal required investigation.

## References

- [Spring Security OAuth 2.0 Resource Server JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [OWASP API Security Top 10 2023](https://owasp.org/API-Security/editions/2023/en/0x11-t10/)
