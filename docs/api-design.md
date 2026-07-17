# LedgerFlow MVP API Design

- Status: Implemented for public orders and secured operator recovery
- Last updated: 2026-07-17
- Authoritative contract: `application/src/main/openapi/ledgerflow.yaml`

The OpenAPI document is authoritative for request/response schemas and examples. This document
explains the durability, recovery, and compatibility semantics that are easy to misread from an
HTTP schema alone. Kafka publication and notification processing are asynchronous; no public
response claims that either completed.

## Conventions and authorization

- Base path: `/api/v1`
- Success media type: `application/json`
- Error media type: RFC 9457 `application/problem+json`
- Timestamps: RFC 3339 UTC instants
- IDs: opaque UUIDs; PostgreSQL business IDs are UUIDv7
- Money: positive `int64` minor units plus explicit `INR`
- Unknown/duplicate JSON properties and undocumented query parameters: rejected

Tokens must be RS256 JWTs with the configured exact issuer, `ledgerflow-api` audience, valid time
claims, route scope, and `customer` or `admin` realm role. POST requires
`ledgerflow.orders.write`; GET requires `ledgerflow.orders.read`. JWT `sub` owns the resource. No
caller-supplied customer ID is accepted. Missing and differently owned orders return the same
`404`. Operator reads require `ledgerflow.operations.read` plus `operator` or `admin`; retry writes
require `ledgerflow.operations.retry` plus `operator` or `admin`; break-glass approval requires
`ledgerflow.operations.break-glass` plus `admin`. A customer role cannot cross this boundary even
if its token contains an operation scope.

`X-Correlation-Id` is optional and must match `[A-Za-z0-9._-]{1,64}`. Invalid/absent values are
replaced. Every response contains the selected ID. The API uses no-store, content/type/frame,
referrer, permissions, and cross-origin security headers; HSTS is emitted only for HTTPS.

POST accepts only uncompressed `application/json`, defaults to a 16 KiB body limit, and is bounded
for headers, nesting, tokens, names, strings, and numbers. A per-instance subject-hash limiter
returns `429`/`Retry-After`; deployment ingress must provide aggregate and unauthenticated limits.

## Idempotency contract

`POST /api/v1/orders` requires a case-sensitive 8–128 character `Idempotency-Key` matching
`[A-Za-z0-9._:-]+`. The durable scope is:

```text
{JWT subject, CREATE_ORDER_V1, SHA-256(validated key bytes)}
```

The version-2 request fingerprint is SHA-256 over a length-delimited fixed-order encoding of:

1. `clientReference` presence and UTF-8 value;
2. `amountMinor` as signed 64-bit integer;
3. uppercase ASCII currency; and
4. the opaque ASCII `paymentMethodReference`.

JSON order/whitespace, authorization, correlation, and trace headers are excluded. Raw keys and
request bodies are never persisted or logged.

The initial short transaction claims the unique key, inserts a `PAYMENT_PROCESSING` order and one
payment with its stable authorization operation ID, and attaches the order ID to the in-progress
record. It commits before provider I/O. This intentionally supersedes ADR 0003's historical
single-transaction `CREATED` snapshot for new version-2 public requests; existing completed
snapshots remain replayable data.

After a terminal/retry-pending result, a short transaction writes the original status, location,
and business representation. Matching replay returns those business fields with the current
request's correlation header and `Idempotency-Replayed: true`. Changed canonical input returns
`409 idempotency_key_reused`. A contender that observes a recent active provider call waits only a
bounded interval and otherwise returns retryable `503`; it never starts a second provider call.

## Create and complete order

```http
POST /api/v1/orders HTTP/1.1
Authorization: Bearer <customer-token>
Content-Type: application/json
Idempotency-Key: order-019535d9-3df7-79fb
X-Correlation-Id: checkout-order-001

{
  "clientReference": "checkout-20260715-0001",
  "amount": {"amountMinor": 259900, "currency": "INR"},
  "paymentMethodReference": "pm_mock_success"
}
```

`paymentMethodReference` is required, 9–128 characters, and matches `pm_mock_[a-z_]+`. It is an
opaque local/test provider token, not card data. It is never returned or logged and is cleared from
the payment after authorization resolves. PAN, CVV, real credentials, or arbitrary payment fields
are rejected.

A normal success returns after local financial finalization:

```http
HTTP/1.1 201 Created
Location: /api/v1/orders/019535d9-3df7-79fb-b466-fa907fa17f9e
X-Correlation-Id: checkout-order-001
Content-Type: application/json

{
  "orderId": "019535d9-3df7-79fb-b466-fa907fa17f9e",
  "clientReference": "checkout-20260715-0001",
  "status": "COMPLETED",
  "amount": {"amountMinor": 259900, "currency": "INR"},
  "payment": {
    "paymentId": "019535d9-4a20-7e29-9d35-a6e4aeab9e07",
    "status": "CAPTURED",
    "failureCode": null
  },
  "createdAt": "2026-07-15T17:24:00Z",
  "updatedAt": "2026-07-15T17:24:01Z"
}
```

`COMPLETED`/`CAPTURED` means one balanced journal and one logical payment-captured outbox event are
durable. The outbox may still be `PENDING`; Kafka acknowledgement and notification creation are not
awaited.

Confirmed authorization/capture decline returns `201` with order `PAYMENT_DECLINED`, the matching
payment decline state, and a stable sanitized failure code. It creates no ledger or outbox effect.
Exhausted temporary failure or unresolved unknown outcome returns `202` with order
`PAYMENT_RETRY_PENDING`; the original `202` is replayable and later recovery requires approved
operator work. A malformed/contradictory provider response persists order/payment `FAILED` and
returns replayable `502 provider_protocol_error` with `Location`; the owner can GET the failed
resource. Provider response text is never returned.

Timeout is not failure proof. LedgerFlow first looks up the persisted operation ID. Confirmed
success continues without a resend. Only `NOT_FOUND` permits a resend with exactly the same ID.
After provider success but before local persistence, a later request waits out the active-call
deadline, performs that lookup, and continues. After the ledger/outbox transaction, a later request
replays those unique effects and completes final states.

## Get order

`GET /api/v1/orders/{orderId}` returns the owner-visible current order/payment representation. It
can show `PAYMENT_PROCESSING` during a durable in-progress recovery window, but terminal create
responses are `COMPLETED`, `PAYMENT_DECLINED`, `PAYMENT_RETRY_PENDING`, or `FAILED`. It does not
include payment-method/provider references or notification-delivery status.

## Secured operator recovery

`GET /api/v1/operator/operations` returns a bounded keyset page of sanitized payment, outbox, and
DLT failures. Optional `type`, `limit` (1–100), and opaque `cursor` parameters do not expose source
payloads or customer identity. IDs are typed opaque values such as `payment_<uuid>`,
`outbox_<uuid>`, and `dead-letter_<uuid>`; changing the prefix cannot reveal another operation
domain. `GET /api/v1/operator/operations/{operationId}` adds at most 200 sanitized provider,
publisher, legacy replay, and operator attempt entries.

`POST /api/v1/operator/operations/{operationId}/retries` requires the normal 8–128 character
`Idempotency-Key` and a 10–500 character control-free audit reason. An optional `approvalId`
consumes separate break-glass evidence. The transactional scope is authenticated issuer/subject,
hashed key, typed operation identity, reason, and approval ID. Matching reuse returns the original
command; changed reuse returns `409`. Database uniqueness permits one active command per
operation. Accepted commands return `202`, `Location`, and a sanitized status representation;
status is readable at `GET .../retries/{retryId}` without returning actor, reason, hashes, lease
tokens, source bodies, or provider data.

Failures establish a five-minute default cooldown. The default automatic command limit is three.
After that limit, an admin records immutable evidence through
`POST .../{operationId}/break-glass-approvals`; this does not execute recovery. A later retry-scope
request may consume the approval once. Approval and use are distinct audit events, and the default
break-glass execution limit is two. Malformed/non-replayable DLT evidence and operations that have
already converged return `409` rather than exposing a direct mutation path.

Payment recovery uses persisted provider operation IDs, looks up first, and permits one same-ID
attempt only after `NOT_FOUND`. Outbox recovery resets only the existing logical event for one new
bounded publisher cycle and lets the normal publisher work asynchronously.
DLT recovery preserves stored event ID, key, and canonical body while replacing only retry
transport metadata. A completed retry means the type-specific durable condition was met; it does
not create an end-to-end exactly-once claim.

These operator routes are additive to API version 1. The local `scripts/replay-dead-letter`
arguments intentionally changed from caller-supplied actor identity to idempotency key because
caller-asserted audit identity was unsafe; automation must now supply a short-lived bearer token.
This is a security-breaking development-tool change, not a change to the public customer contract.

## Problem details

Every problem uses `type`, `title`, `status`, `detail`, and `instance`, plus stable `code` and the
current `correlationId`. Validation can add bounded field/code entries.

| Status | Meaning |
| ---: | --- |
| `400` | Invalid body/path/header/query or idempotency-key syntax |
| `401` | Missing or invalid bearer authentication |
| `403` | Required scope or realm role absent |
| `404` | Order absent/not owned, or typed operator resource absent |
| `406` | Response media type unsupported |
| `409` | Idempotency mismatch or recovery state/cooldown/limit/approval conflict |
| `413` | Payload exceeds configured bound |
| `415` | Media type/content encoding unsupported |
| `422` | Currency is well formed but not INR |
| `429` | Per-instance subject write limit exceeded |
| `500` | Sanitized unexpected failure; retry same key if outcome is uncertain |
| `502` | Malformed/contradictory provider response; failed order is durable at `Location` |
| `503` | Durable dependency unavailable or another provider call is still safely active |

Problems/logs never expose keys, hashes, request bodies, tokens, SQL, stack traces, provider bodies,
payment-method references, PANs, or CVVs.

## Compatibility

Adding `paymentMethodReference` changes the create request and terminal representation within the
pre-release MVP v1 contract. ADR 0013 records the explicit maintainer-approved break from the
historical Milestone 3 slice. There is no production compatibility promise yet. Database migration
V008 is additive/forward-only and continues to permit historical `CREATED` rows and completed 201
snapshots. Kafka event schema version 1 is unchanged.
