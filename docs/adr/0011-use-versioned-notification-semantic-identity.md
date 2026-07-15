# ADR 0011: Separate Transport and Notification Semantic Idempotency

- Status: Accepted
- Date: 2026-07-15

## Context

ADR 0006 used event ID plus canonical hash to make redelivery of one event envelope idempotent. A producer, replay tool, or compromised principal could place the same captured-payment meaning in a new envelope with a new event ID, creating another notification. Uniqueness on event type and payment ID alone would block legitimate future effects such as multiple captures or versioned notification purposes.

## Decision

Retain `notification_inbox.event_id` and its canonical payload-hash conflict check as transport idempotency. Add a distinct versioned semantic-effect identity to `notifications`, enforced by a database unique constraint.

For `PAYMENT_CAPTURED_NOTIFICATION` identity version 1, the effect key is the immutable capture ledger transaction ID. Trusted validated content compared on collision is order ID, payment ID, provider-capture causation ID, amount, currency, and capture occurrence time. Event ID, transport correlation, and trace headers do not define the business effect.

A new event ID with the same identity and content commits a second inbox row marked `SEMANTIC_DUPLICATE` and no second notification. The same semantic identity with conflicting content raises an integrity error and rolls back that new inbox row. Concurrent envelopes converge through the database unique constraint. Future event effects must define and review their own type, identity version, key, and compared content.

## Consequences

- At-least-once delivery and re-enveloping cannot repeat the covered notification database effect.
- Transport duplicates and semantic duplicates remain distinguishable operational evidence.
- A semantic collision with conflicting content is a non-replayable integrity incident, not a retryable transient error.
- V006 fails closed if legacy notifications cannot be mapped to immutable capture evidence or already contain duplicate semantic effects; reconciliation requires a separately reviewed forward migration.
- This control does not replace Kafka producer authentication, topic ACLs, or event validation and does not create an end-to-end exactly-once guarantee.

## Alternatives considered

- Event-ID uniqueness alone was rejected because event ID is envelope identity, not business-effect identity.
- `(event_type, payment_id)` was rejected because it would over-constrain legitimate future event purposes or multiple capture effects.
- An application-only pre-insert check was rejected because concurrent instances could both observe absence and insert.
