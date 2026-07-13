# LedgerFlow MVP API Design

- Status: Proposed
- Last updated: 2026-07-11
- Public OpenAPI source: `application/src/main/openapi/ledgerflow.yaml`
- Future mock-provider OpenAPI source: `application/src/testFixtures/openapi/mock-payment-provider.yaml`

The OpenAPI document created during implementation is authoritative. This design fixes the contract choices that the OpenAPI document must express.

## Conventions

- Base path: `/api/v1`
- Media type: `application/json`
- Error media type: `application/problem+json` following RFC 9457
- Authentication: OAuth 2.0 bearer JWT validated by issuer, audience, signature, and time claims
- Timestamps: RFC 3339 UTC instants ending in `Z`
- IDs: UUID strings treated as opaque by clients
- Money: `amountMinor` with OpenAPI `int64` plus three-letter `currency`
- Unknown JSON properties: rejected with `400` to expose client mistakes

### Authorization scopes

| Scope | Capability |
| --- | --- |
| `ledgerflow.orders.write` | Create an order for JWT `sub` |
| `ledgerflow.orders.read` | Read orders owned by JWT `sub` |
| `ledgerflow.operations.read` | List and inspect failed operations |
| `ledgerflow.operations.retry` | Request an operation retry |

Operator scopes grant access only to sanitized operation resources, not customer order representations. The API never trusts a caller-supplied customer identifier.

### Correlation and trace headers

- `X-Correlation-Id` is optional on requests and always present on responses.
- Valid values are 1–64 characters matching `[A-Za-z0-9._-]+`; absent or invalid values are replaced rather than reflected.
- `traceparent` and `tracestate` follow W3C Trace Context and are handled by OpenTelemetry instrumentation.
- Correlation IDs are operational identifiers, not authentication or idempotency credentials.

### Idempotency header

- `Idempotency-Key` is required on both write operations described below.
- Valid values are 8–128 characters matching `[A-Za-z0-9._:-]+`.
- Clients should generate at least 128 bits of unpredictable entropy, such as a UUID. The stored SHA-256 hash minimizes raw-key exposure but is not password hashing or authentication.
- Header values are case-sensitive and hashed as validated UTF-8 bytes.
- Scope is `{authenticated subject, OpenAPI operation plus concrete target resource ID where present, SHA-256(key)}`.
- The create-order fingerprint is SHA-256 over a versioned, fixed-field-order canonical encoding of `clientReference` presence/value, `amountMinor`, `currency`, and `paymentMethodToken`. JSON whitespace and property order do not affect it. Authorization, correlation, trace, and other transport headers are excluded.
- An accepted result snapshots status, response body, and `Location`. Replay returns that snapshot plus a current response correlation ID and `Idempotency-Replayed: true`.

## Create order

`POST /api/v1/orders`

Required scope: `ledgerflow.orders.write`

### Request

```http
POST /api/v1/orders HTTP/1.1
Host: api.ledgerflow.example
Authorization: Bearer <token>
Content-Type: application/json
Accept: application/json
Idempotency-Key: order-demo-01HZY8K7T6FX9KQJ
X-Correlation-Id: demo-order-001

{
  "clientReference": "checkout-20260711-0001",
  "amount": {
    "amountMinor": 2599,
    "currency": "USD"
  },
  "paymentMethodToken": "pm_mock_success"
}
```

Validation:

- `clientReference` is optional and 1–100 characters. It is an indexed, non-unique client correlation value and has no idempotency semantics.
- `amountMinor` is an integer from 1 through `9223372036854775807`.
- `currency` must be `USD` in the MVP.
- `paymentMethodToken` is 1–128 opaque characters and must not contain card data. Local/test profiles accept only documented `pm_mock_*` tokens.
- The maximum request body is 16 KiB.

### Successful capture

```http
HTTP/1.1 201 Created
Content-Type: application/json
Location: /api/v1/orders/550e8400-e29b-41d4-a716-446655440001
X-Correlation-Id: demo-order-001

{
  "orderId": "550e8400-e29b-41d4-a716-446655440001",
  "status": "COMPLETED",
  "amount": {
    "amountMinor": 2599,
    "currency": "USD"
  },
  "payment": {
    "paymentId": "550e8400-e29b-41d4-a716-446655440002",
    "status": "CAPTURED"
  },
  "createdAt": "2026-07-11T19:00:00Z",
  "updatedAt": "2026-07-11T19:00:01Z"
}
```

### Terminal decline

The order resource was created successfully, so a provider business decline is represented in the resource rather than as an HTTP transport error.

```http
HTTP/1.1 201 Created
Content-Type: application/json
Location: /api/v1/orders/550e8400-e29b-41d4-a716-446655440003
X-Correlation-Id: demo-order-002

{
  "orderId": "550e8400-e29b-41d4-a716-446655440003",
  "status": "PAYMENT_DECLINED",
  "amount": {
    "amountMinor": 2599,
    "currency": "USD"
  },
  "payment": {
    "paymentId": "550e8400-e29b-41d4-a716-446655440004",
    "status": "DECLINED",
    "failureCode": "PAYMENT_DECLINED"
  },
  "createdAt": "2026-07-11T19:02:00Z",
  "updatedAt": "2026-07-11T19:02:00Z"
}
```

Provider messages and codes are mapped to LedgerFlow's stable failure codes and are not returned verbatim.

### Retry-pending outcome

```http
HTTP/1.1 202 Accepted
Content-Type: application/json
Location: /api/v1/orders/550e8400-e29b-41d4-a716-446655440005
X-Correlation-Id: demo-order-003

{
  "orderId": "550e8400-e29b-41d4-a716-446655440005",
  "status": "PAYMENT_RETRY_PENDING",
  "amount": {
    "amountMinor": 2599,
    "currency": "USD"
  },
  "payment": {
    "paymentId": "550e8400-e29b-41d4-a716-446655440006",
    "status": "CAPTURE_UNKNOWN",
    "failureCode": "PROVIDER_OUTCOME_UNKNOWN"
  },
  "operationId": "550e8400-e29b-41d4-a716-446655440007",
  "createdAt": "2026-07-11T19:03:00Z",
  "updatedAt": "2026-07-11T19:03:03Z"
}
```

The `202` snapshot remains the idempotent POST result even if an operator later completes the order. Clients use the order GET endpoint for current state.

### Non-retryable provider protocol failure

A malformed, contradictory, or amount/currency-mismatched provider response after durable order creation produces a cached problem result with the order location:

```http
HTTP/1.1 502 Bad Gateway
Content-Type: application/problem+json
Location: /api/v1/orders/550e8400-e29b-41d4-a716-44665544000b
X-Correlation-Id: demo-order-005

{
  "type": "https://ledgerflow.example/problems/provider-protocol-error",
  "title": "Payment provider protocol error",
  "status": 502,
  "detail": "The payment provider returned an invalid response.",
  "instance": "/api/v1/orders",
  "code": "provider_protocol_error",
  "correlationId": "demo-order-005",
  "orderId": "550e8400-e29b-41d4-a716-44665544000b",
  "orderStatus": "FAILED",
  "paymentStatus": "FAILED"
}
```

The raw provider response is not exposed. Replaying the original POST returns this same `502` business result and order ID with a fresh transport correlation ID. The owner can inspect current state through the `Location`; the failure is visible but non-retryable through the operator API.

### Idempotent replay

```http
HTTP/1.1 201 Created
Content-Type: application/json
Location: /api/v1/orders/550e8400-e29b-41d4-a716-446655440001
Idempotency-Replayed: true
X-Correlation-Id: replay-attempt-001

<the original response body>
```

No provider, ledger, or outbox operation runs during replay.

### Idempotency conflict

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json
X-Correlation-Id: demo-order-004

{
  "type": "https://ledgerflow.example/problems/idempotency-key-reused",
  "title": "Idempotency key reused",
  "status": 409,
  "detail": "The idempotency key was already used with a different request.",
  "instance": "/api/v1/orders",
  "code": "idempotency_key_reused",
  "correlationId": "demo-order-004"
}
```

The response never exposes stored hashes or the original payload.

## Get order

`GET /api/v1/orders/{orderId}`

Required scope: `ledgerflow.orders.read`. Returns the current order representation shown above only to the JWT subject that owns it.

Responses:

- `200 OK` for an owned order;
- `400 Bad Request` for malformed IDs;
- `401 Unauthorized` for missing or invalid authentication; and
- `404 Not Found` for absent and non-owned orders, preventing resource enumeration.

## List failed operations

`GET /api/v1/operator/operations?status=OPEN&type=PAYMENT_CAPTURE&limit=50&after=<cursor>`

Required scope: `ledgerflow.operations.read`

Filters are optional. `limit` is 1–100 and defaults to 50. Cursor pagination uses an opaque stable cursor ordered by `lastFailedAt DESC, operationId DESC`.

```json
{
  "items": [
    {
      "operationId": "550e8400-e29b-41d4-a716-446655440007",
      "type": "PAYMENT_CAPTURE",
      "resourceType": "PAYMENT",
      "resourceId": "550e8400-e29b-41d4-a716-446655440006",
      "status": "OPEN",
      "retryable": true,
      "attemptCount": 2,
      "failureCode": "PROVIDER_OUTCOME_UNKNOWN",
      "firstFailedAt": "2026-07-11T19:03:02Z",
      "lastFailedAt": "2026-07-11T19:03:03Z",
      "correlationId": "demo-order-003"
    }
  ],
  "nextCursor": null
}
```

`GET /api/v1/operator/operations/{operationId}` returns the same safe fields plus retry history. For notification-consume failures it also returns a `deadLetter` object with always-present original topic/partition/offset, payload hash/size, replayable flag, attempt count, and dead-lettered time; parsed event ID is optional for malformed input. It never returns stack traces, payment tokens, raw Kafka payloads, or provider response bodies.

## Request operation retry

`POST /api/v1/operator/operations/{operationId}/retries`

Required scope: `ledgerflow.operations.retry`

```http
POST /api/v1/operator/operations/550e8400-e29b-41d4-a716-446655440007/retries HTTP/1.1
Authorization: Bearer <operator-token>
Content-Type: application/json
Idempotency-Key: retry-550e8400-01
X-Correlation-Id: operator-retry-001

{
  "reason": "Provider status verified; retry requested by incident LF-42."
}
```

`reason` is required, trimmed, and 10–500 characters.

```http
HTTP/1.1 202 Accepted
Content-Type: application/json
Location: /api/v1/operator/operations/550e8400-e29b-41d4-a716-446655440007
X-Correlation-Id: operator-retry-001

{
  "retryRequestId": "550e8400-e29b-41d4-a716-446655440008",
  "operationId": "550e8400-e29b-41d4-a716-446655440007",
  "status": "ACCEPTED",
  "operationStatus": "RETRY_REQUESTED",
  "acceptedAt": "2026-07-11T19:05:00Z"
}
```

Rules:

- replaying the retry command returns the original `202` result;
- a different payload with the same key returns `409 idempotency_key_reused`;
- a resolved or non-retryable operation returns `409 operation_not_retryable`;
- an already retrying operation returns `409 operation_retry_in_progress` unless the request is an idempotent replay; and
- retry actions are audited with operator subject, reason, correlation ID, and timestamps.

## Problem response catalog

| HTTP status | Stable code | Use |
| --- | --- | --- |
| `400` | `invalid_request` | Malformed JSON/schema types or a missing/malformed required idempotency header |
| `401` | `unauthorized` | Missing or invalid bearer token |
| `403` | `forbidden` | Authenticated principal lacks required scope |
| `404` | `resource_not_found` | Resource absent or hidden by ownership rules |
| `409` | `idempotency_key_reused` | Same scoped key, different fingerprint |
| `409` | `idempotency_request_in_progress` | Matching request is still being processed after bounded wait |
| `409` | `operation_not_retryable` | Retry is not allowed from current state |
| `409` | `operation_retry_in_progress` | Another retry has already been accepted |
| `422` | `unsupported_currency` | Well-formed money uses a currency not supported by MVP |
| `502` | `provider_protocol_error` | Provider returned a malformed or contradictory non-retryable response after the order was durably created |
| `500` | `internal_error` | Unexpected failure with no exposed internals |
| `503` | `service_unavailable` | Request could not be durably accepted because required infrastructure was unavailable |

Every problem body contains `type`, `title`, `status`, `detail`, `instance`, `code`, and `correlationId`. Validation problems add an `errors` array of stable field pointers and codes.

## Mock payment-provider contract

The mock provider is a separate local/test HTTP support service, not part of LedgerFlow's public `/api/v1` contract or production deployment.

It supports:

- `POST /provider/v1/authorizations`;
- `POST /provider/v1/captures`;
- `GET /provider/v1/operations/{operationKey}` for reconciliation.

Authorization requests contain a stable authorization operation key, payment/order references, money, and the opaque payment-method reference. Capture requests contain a distinct stable capture operation key, the validated provider authorization ID, payment/order references, and money; they do not resend the payment-method reference. The provider treats each operation key idempotently.

Local/test scenario tokens:

| Token | Behavior |
| --- | --- |
| `pm_mock_success` | Authorization and capture succeed immediately |
| `pm_mock_latency` | Each call succeeds after 500 ms, below the 1-second client timeout |
| `pm_mock_timeout` | Capture succeeds provider-side, the response times out, the first lookup is temporarily unavailable, and a later lookup by the same operation key returns the stored success |
| `pm_mock_authorization_decline` | Authorization is declined |
| `pm_mock_temporary_failure` | The first authorization call and first capture call each return `503`; the automatic retry for that stage with the same operation key succeeds |
| `pm_mock_persistent_temporary_failure` | Authorization's initial and automatic attempts return `503`; the next operator-authorized authorization attempt with the same key succeeds, then capture succeeds |

Scenario selection through payment tokens is enabled only by explicit `local`, `test`, or `demo` configuration. The separate fixture and its control code are excluded from the LedgerFlow main artifact. Production configuration rejects the mock endpoint and remains a launch blocker until a real provider integration is approved.

## Contract compatibility

- Additive optional fields are allowed within v1.
- Existing required request fields, response meanings, enum values, and error semantics are not removed or changed without a versioning ADR.
- Kafka event schemas have independent explicit versions and are not assumed to match HTTP representations.
- `openApiValidate` validates both public and mock-provider documents. Integration contract tests are required; server code generation is not used in the MVP.

## References

- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457.html)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
