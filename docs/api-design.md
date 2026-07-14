# LedgerFlow MVP API Design

- Status: Partially implemented
- Last updated: 2026-07-14
- Authoritative contract: `application/src/main/openapi/ledgerflow.yaml`

This document explains the active Create Order slice. The OpenAPI document is authoritative for
request and response schemas. Payments, provider simulation, ledger posting, Kafka, notifications,
and operator recovery remain future milestones and are not exposed by the application yet.

## Conventions

- Base path: `/api/v1`
- Success media type: `application/json`
- Error media type: `application/problem+json`, using RFC 9457 fields
- Authentication: OAuth 2.0 bearer JWT
- Timestamps: RFC 3339 UTC instants
- IDs: opaque UUID strings; order IDs are PostgreSQL 18 UUIDv7 values
- Money: positive `int64` minor units plus a three-letter currency; the MVP accepts `INR` only
- Unknown JSON properties: rejected with `400`

Tokens must be RS256-signed JWTs with the configured exact issuer, audience `ledgerflow-api`, valid
expiry/not-before claims, and both the route scope and an allowlisted Keycloak realm role. The
order-write scope is `ledgerflow.orders.write`; the order-read scope is
`ledgerflow.orders.read`. Active order routes permit `customer` or `admin`, but not `operator`
alone. The authenticated JWT `sub` owns the resource. A caller-supplied customer identifier is
never accepted. Future `/api/v1/operator/**` routes are already fail-closed behind
`ledgerflow.operations.read` or `ledgerflow.operations.retry` plus `operator` or `admin`, but no
operator HTTP operation is implemented or present in OpenAPI.

`X-Correlation-Id` is optional. A value must contain 1–64 characters matching
`[A-Za-z0-9._-]+`; otherwise LedgerFlow replaces it. Every response carries the selected value and
structured application logs include it.

Responses use Spring's safe API header baseline plus `Content-Security-Policy: default-src 'none'`,
`Referrer-Policy: no-referrer`, restrictive permissions and cross-origin policies, MIME sniffing
protection, frame denial, and no-store/no-cache directives. HTTPS responses also carry one-year
HSTS with subdomains; HTTP development responses deliberately do not.

## Request and resource limits

Order operations accept no undocumented query parameters. `POST /api/v1/orders` accepts only
`application/json` and no compressed request body. It rejects duplicate or unknown JSON
properties, excessive nesting/token/string or
number lengths, and a body larger than the configured limit (16 KiB by default). The server also
limits aggregate request headers to 16 KiB. Rejections are bounded problem details and occur before
the order transaction.

Each application instance applies a fixed-window limit to create attempts per authenticated
subject (60 per minute by default). Only a bounded number of SHA-256 subject hashes is retained;
raw subjects, bearer tokens, and idempotency keys are not rate-limit state. The first request over
the limit receives `429`, `Retry-After`, and the correlation ID without a business write. This is
per-instance defense in depth. A trusted ingress must enforce aggregate and unauthenticated
volumetric limits across a production deployment.

## Idempotency contract

`POST /api/v1/orders` requires a case-sensitive `Idempotency-Key` containing 8–128 characters
matching `[A-Za-z0-9._:-]+`.

The durable scope is:

```text
{JWT subject, CREATE_ORDER_V1, SHA-256(validated key bytes)}
```

Raw keys and raw request payloads are not persisted or logged. The request fingerprint is SHA-256
over a versioned, length-delimited encoding of these validated fields in fixed order:

1. `clientReference` presence and UTF-8 value;
2. `amountMinor` as a signed 64-bit integer; and
3. `currency` as uppercase ASCII.

JSON whitespace, JSON property order, authorization, correlation, and trace headers are excluded.
Null and an empty client reference have different canonical encodings, although an empty value is
rejected at the API boundary.

The key claim, new `CREATED` order, and immutable `201` response snapshot commit in one PostgreSQL
transaction. A database primary key on `(principal_scope, operation, key_hash)` serializes
contenders. PostgreSQL waits for an uncommitted conflicting insert: after the winner commits, an
identical request replays its snapshot and a changed request returns `409`. If the winner rolls
back, a contender can claim the key and create the order. No provider or Kafka call occurs in this
transaction.

## Create order

`POST /api/v1/orders`

```http
POST /api/v1/orders HTTP/1.1
Authorization: Bearer <customer-token>
Content-Type: application/json
Idempotency-Key: order-019535d9-3df7-79fb
X-Correlation-Id: checkout-order-001

{
  "clientReference": "checkout-20260713-0001",
  "amount": {
    "amountMinor": 259900,
    "currency": "INR"
  }
}
```

Validation rules:

- `clientReference` is optional, 1–100 characters when present, and has no surrounding whitespace;
- `amountMinor` is a positive signed 64-bit integer;
- `currency` is the explicit value `INR`; and
- the request contains no payment method because payment behavior is outside this slice.

A new order returns:

```http
HTTP/1.1 201 Created
Content-Type: application/json
Location: /api/v1/orders/019535d9-3df7-79fb-b466-fa907fa17f9e
X-Correlation-Id: checkout-order-001

{
  "orderId": "019535d9-3df7-79fb-b466-fa907fa17f9e",
  "clientReference": "checkout-20260713-0001",
  "status": "CREATED",
  "amount": {
    "amountMinor": 259900,
    "currency": "INR"
  },
  "createdAt": "2026-07-13T09:42:00Z",
  "updatedAt": "2026-07-13T09:42:00Z"
}
```

An identical replay returns the same `201`, body, `Location`, and order ID, with the current
operation's correlation header and:

```http
Idempotency-Replayed: true
```

A changed-payload reuse returns:

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json
X-Correlation-Id: checkout-order-002

{
  "type": "https://ledgerflow.example/problems/idempotency-key-reused",
  "title": "Idempotency key reused",
  "status": 409,
  "detail": "The idempotency key was already used with a different request.",
  "instance": "/api/v1/orders",
  "code": "idempotency_key_reused",
  "correlationId": "checkout-order-002"
}
```

## Get order

`GET /api/v1/orders/{orderId}` returns only an order owned by the authenticated subject.

```http
GET /api/v1/orders/019535d9-3df7-79fb-b466-fa907fa17f9e HTTP/1.1
Authorization: Bearer <customer-token>
X-Correlation-Id: checkout-read-001
```

The `200` body uses the same order schema. Missing and non-owned IDs both return `404` so the API
does not disclose another subject's resource. A malformed UUID returns `400`.

## Problem details

Every error uses the RFC 9457 members `type`, `title`, `status`, `detail`, and `instance`, plus stable
LedgerFlow properties `code` and `correlationId`. Validation failures can also contain an `errors`
array of JSON-style field paths and stable codes.

The active operations document these responses:

| Status | Meaning |
| ---: | --- |
| `400` | Invalid body, UUID, required header, idempotency-key syntax, unexpected query, or validation |
| `401` | Missing or invalid bearer authentication |
| `403` | Authenticated token lacks the required scope or allowlisted realm role |
| `404` | Order does not exist or is not owned by the subject |
| `406` | Requested response media type is unsupported |
| `409` | Idempotency key is bound to a different canonical payload |
| `413` | Request payload exceeds the configured byte limit |
| `415` | Request media type or content encoding is unsupported |
| `422` | A well-formed currency is unsupported; the MVP accepts INR only |
| `429` | Per-instance subject write limit exceeded; retry after the response delay |
| `500` | Sanitized unexpected server failure |
| `503` | PostgreSQL or safe idempotency completion is temporarily unavailable |

Problem responses never expose hashes, original payloads, raw keys, tokens, SQL, or stack traces.

## Compatibility and deferred behavior

The current response state is only `CREATED`. Later milestones may add payment-related current
states and fields only after updating and validating OpenAPI first. They must preserve the original
Create Order replay snapshot and keep `GET` as the current-state read. Provider, ledger, outbox,
Kafka, notification, and operator routes described in product planning are not active contracts.
