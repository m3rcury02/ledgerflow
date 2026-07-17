# LedgerFlow Flyway Migration Inventory

Migrations are forward-only and applied in filename order from
`application/src/main/resources/db/migration`. Never edit, rename, reorder, or delete one after it
has merged. Fixes require a new migration. Tests migrate a fresh PostgreSQL 18 database; selected
compatibility tests seed historical shapes before later migrations.

| Version | File | Purpose and key invariants | Rollback / compatibility note |
| --- | --- | --- | --- |
| V001 | `V001__create_orders_and_idempotency.sql` | Orders, customer ownership, positive INR money, UTC timestamps, durable hashed create-order idempotency and original response snapshot. | Baseline is destructive to undo; restore a pre-migration database rather than dropping financial data. |
| V002 | `V002__create_payment_tables.sql` | Explicit payment states, stable authorization/capture IDs, optimistic version, append-only attempt history, state-shape and order-money consistency. | Additive from V001; rollback would remove recovery/audit evidence and is not supported after use. |
| V003 | `V003__create_immutable_ledger.sql` | Seeded clearing/payable accounts, immutable journal headers/entries, unique capture identity, positive INR amounts, deferred balance and exact-reversal checks. | Posted journals are audit data. Correct with compensating journals or restore; never down-migrate by mutating entries. |
| V004 | `V004__create_transactional_outbox.sql` | Versioned outbox envelope storage, logical deduplication key, payload hash, leases, bounded attempt state, and immutability guards. | Removing it would break capture atomicity. Forward-fix state/lease behavior with a new migration. |
| V005 | `V005__create_notification_inbox_and_dead_letters.sql` | Transport inbox/hash identity, notification side effect, replayable sanitized DLT catalog, and immutable replay audit. | Existing inbox/DLT/audit rows must be retained during forward upgrade. |
| V006 | `V006__add_notification_semantic_effect_identity.sql` | Versioned semantic-effect identity/hash and conflict-safe uniqueness independent of transport event ID. | Compatibility migration fails closed if historical rows cannot be mapped unambiguously; do not drop transport uniqueness. |
| V007 | `V007__record_terminal_dlt_evidence.sql` | Immutable sanitized terminal evidence keyed by actual DLT topic/partition/offset, including bounded hashes/sizes and safe headers. | Additive. Evidence is operational audit data and is not silently discarded on rollback. |
| V008 | `V008__finalize_public_order_workflow.sql` | Payment-processing/retry/failed order states, workflow idempotency fingerprint version, and deferred cross-table checks that `COMPLETED` has captured payment, journal, and outbox evidence. | Preserves historical completed snapshots; a rollback cannot safely infer or erase external provider effects. Forward-fix only. |
| V009 | `V009__secure_operator_recovery.sql` | Typed recovery state, idempotent retry commands, owner/token/version leases, immutable privileged audit, cooldown/caps, and separate break-glass approval/use evidence. | Additive operational data. Roll back application binaries only if schema remains compatible; do not delete audit/recovery history. |

## Validation

```bash
./gradlew --no-daemon :application:integrationTest \
  --tests '*LedgerFlowApplicationIntegrationTest' \
  --tests '*MigrationCompatibilityIntegrationTest' \
  --console=plain
```

`./gradlew clean verify` is the authoritative empty-schema and compatibility gate. No Milestone 8
schema change was required; proof hooks and documentation introduce no migration.
