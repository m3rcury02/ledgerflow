# ADR 0005: Enforce an Immutable Balanced Double-Entry Ledger

- Status: Proposed
- Date: 2026-07-11
- Decision owners: LedgerFlow maintainers

## Context

A captured payment must create balanced debit and credit entries exactly once. Application-only validation can be bypassed by defects, concurrent code, scripts, or later integrations. A row-level `CHECK` constraint cannot validate totals across all entries in a ledger transaction.

The MVP does not establish that LedgerFlow is merchant-of-record, so recognizing revenue would be an unsupported accounting assumption.

## Decision

Represent each confirmed capture as one immutable ledger transaction containing exactly two positive INR entries:

- debit `PAYMENT_CLEARING` (asset); and
- credit `MERCHANT_PAYABLE` (liability).

The entries have equal integer minor-unit amounts and the same currency as the order and payment. Unique `(source_type, source_id)` and a unique payment reference make local posting idempotent.

Domain code validates the posting before persistence. PostgreSQL also enforces:

- positive exact amounts and allowed sides;
- account, transaction, and entry currency equality;
- exactly two MVP entries, one debit and one credit;
- equal debit and credit totals at transaction commit; and
- rejection of update/delete for posted transactions and entries.

The payment-capture source is a real foreign key to the payment, its order must match the ledger transaction, and the two entries must use the exact seeded accounts/sides above. Account code, type, and currency become immutable once referenced.

Because aggregate checks span rows, Flyway creates a `DEFERRABLE INITIALLY DEFERRED` constraint-trigger validator. Constraint triggers on both ledger-transaction insertion and entry insert/update/delete invoke the commit-time validator, so zero-entry and one-entry transactions also fail.

Corrections use new reversing/adjusting transactions rather than mutation. Implementing reversal commands is outside MVP scope.

The ledger transaction and entries commit in the same local transaction as payment `CAPTURED`, order `COMPLETED`, and the outbox event.

## Consequences

### Positive

- The database cannot commit an unbalanced or incomplete ledger transaction.
- Duplicate finalization converges on one logical posting.
- Audit history remains immutable.
- The account choice avoids premature revenue-recognition semantics.

### Costs and risks

- Deferred trigger logic is PostgreSQL-specific and requires direct integration tests.
- Bulk data operations must respect commit-time validation.
- The two-entry rule is MVP-specific and must evolve before fees, taxes, settlement, or split accounting.
- Corrective accounting requires new records rather than convenient updates.

## Alternatives considered

### Validate balance only in Java

Rejected because the financial invariant should survive application defects and direct database access.

### Store one row with debit and credit columns

Rejected because it is not extensible to future multi-entry postings and hides account-oriented double entry.

### Use PostgreSQL `money` or floating point

Rejected because repository governance requires integer minor units and explicit currency.

### Credit an order-revenue account

Rejected because the product brief does not establish merchant-of-record or revenue-recognition policy.
