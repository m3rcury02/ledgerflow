# ADR 0005: Enforce an Immutable Balanced Double-Entry Ledger

- Status: Accepted
- Date: 2026-07-11
- Accepted: 2026-07-13
- Decision owners: LedgerFlow maintainers

## Context

A captured payment must create balanced debit and credit entries exactly once. Application-only validation can be bypassed by defects, concurrent code, scripts, or later integrations. A row-level `CHECK` constraint cannot validate totals across all entries in a ledger transaction.

The MVP does not establish that LedgerFlow is merchant-of-record, so recognizing revenue would be an unsupported accounting assumption. The ledger-only milestone must also preserve the later meaning of final payment state `CAPTURED`, which includes order and outbox work that is not yet approved.

## Decision

Represent a journal transaction as an immutable header with two or more positive INR entries. Domain construction requires all entries to use the transaction currency and uses overflow-checked integer minor-unit arithmetic to prove total debits equal total credits before persistence.

Each provider-confirmed capture produces exactly two entries:

- debit `PAYMENT_CLEARING` (asset); and
- credit `MERCHANT_PAYABLE` (liability).

Both entries equal the payment amount. Every journal transaction has real payment and order foreign keys, a source identity, UTC posting time, validated correlation ID, and actor. Unique `(source_type, source_id)` plus a partial unique payment-capture index make local capture posting idempotent.

PostgreSQL also enforces:

- positive exact amounts, allowed sides, and INR currency;
- account, transaction, entry, payment, and order consistency;
- two or more entries and equal debit/credit totals at transaction commit;
- exactly the two seeded accounts and sides for a payment-capture journal; and
- rejection of updates and deletes for posted transaction and entry rows.

Because aggregate checks span rows, Flyway creates `DEFERRABLE INITIALLY DEFERRED` constraint triggers on both journal-header insertion and entry insert/update/delete. The shared commit-time validator therefore rejects empty and one-entry journals as well as unbalanced journals. The application runtime role must not receive DDL or trigger-bypass authority.

Corrections append one `CORRECTION` transaction that exactly reverses every entry in its original payment-capture journal. A unique reversal reference makes the correction command replayable. Posted rows are never repaired in place.

The ledger-only transaction runs at PostgreSQL `READ COMMITTED`. It locks the payment row with `SELECT ... FOR UPDATE`, verifies `CAPTURE_CONFIRMED`, inserts the journal header and entries, and transitions the payment to the interim `CAPTURE_ACCOUNTED` state. Deferred validation runs before commit. Any error rolls back both the ledger and payment state. Same-payment writers serialize on the row lock; database uniqueness is the final duplicate backstop.

Provider I/O never occurs in this transaction. `CAPTURE_ACCOUNTED` means only that provider-confirmed money has an immutable local journal. Final `CAPTURED`, order completion, HTTP finalization, and transactional outbox insertion remain a separate unapproved milestone.

## Consequences

### Positive

- The database cannot commit an unbalanced, incomplete, mismatched, or duplicate capture journal.
- Concurrent duplicate posting converges on one logical result.
- Payment accounting status cannot commit without its journal, or vice versa.
- Audit and correction history remains append-only.
- The account choice avoids premature revenue-recognition semantics.

### Costs and risks

- Deferred trigger logic is PostgreSQL-specific and requires direct integration tests.
- `READ COMMITTED` correctness depends on every capture posting locking the payment first; uniqueness remains necessary as defense in depth.
- The exact two-entry shape is capture-specific; the journal model itself permits additional balanced entries for future approved posting types.
- The current schema supports one exact reversal of an original capture. Partial refunds, fees, adjustments, and reversal-of-reversal behavior require a later accounting design.
- A schema owner can bypass runtime protections; production must use the separately documented least-privilege runtime role.

## Alternatives considered

### Validate balance only in Java

Rejected because the financial invariant should survive application defects and direct database access.

### Use serializable isolation for all posting

Rejected because a per-payment row lock plus unique business keys gives the required same-payment serialization with less abort/retry complexity. Broader cross-payment invariants do not exist in this milestone.

### Store one row with debit and credit columns

Rejected because it is not extensible to future multi-entry postings and hides account-oriented double entry.

### Use PostgreSQL `money` or floating point

Rejected because repository governance requires integer minor units and an explicit currency.

### Credit an order-revenue account

Rejected because the product brief does not establish merchant-of-record or revenue-recognition policy.
