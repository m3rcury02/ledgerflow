# ADR 0008: Provide Secured, Audited, Idempotent Operator Recovery

- Status: Proposed
- Date: 2026-07-11
- Decision owners: LedgerFlow maintainers

## Context

Payment ambiguity, exhausted outbox publication, and dead-lettered notification events must be inspectable and recoverable without direct database edits, arbitrary Kafka commands, or application restarts. Recovery is privileged and can repeat financial or messaging work if invoked incorrectly.

The project does not include an identity provider, but unauthenticated operator endpoints are not an acceptable production design.

## Decision

LedgerFlow acts as an OAuth 2.0 JWT resource server. It validates RS256 signature only, exact configured issuer, `ledgerflow-api` audience, expiry, and not-before. Initial trusted JWKS loading gates readiness and validation fails closed without a valid cached key. The external identity provider is a deployment dependency, not part of the MVP.

Use separate scopes:

- `ledgerflow.orders.write` and `ledgerflow.orders.read` for customer operations;
- `ledgerflow.operations.read` for failed-operation inspection; and
- `ledgerflow.operations.retry` for retry commands.

Operator routes live under `/api/v1/operator/operations` in the OpenAPI contract and must also be placed behind internal/management ingress controls in production.

Modules publish sanitized failure facts to the `operations` module. The operator API exposes stable codes, attempts, safe resource/event coordinates, timestamps, and correlations, never raw provider/Kafka bodies, tokens, stack traces, or secrets.

A retry request:

- is accepted only for an `OPEN`, retryable operation;
- requires operator retry scope, `Idempotency-Key`, and a bounded audit reason;
- stores a hashed key and request fingerprint;
- permits only one active retry per failed operation;
- records the request and append-only audit in the acceptance transaction;
- returns `202 Accepted`; and
- selects the action and original provider/event identifiers server-side.

A leased worker claims accepted requests through owner/version-guarded compare-and-set. Expired work may be taken over; stale workers cannot execute or complete it. Payment resolves only after reconciled provider/local state is durable, outbox only after `PUBLISHED`, and notification only after its transaction commits or confirms the identical prior effect.

Payment retry resumes the persisted stage with the original provider operation key. Outbox retry releases the failed row to `PENDING`. Notification retry republishes the same event ID/payload from the DLT catalog. All downstream effects remain idempotent.

Production startup fails if permissive authentication or the mock-provider profile is enabled.

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
