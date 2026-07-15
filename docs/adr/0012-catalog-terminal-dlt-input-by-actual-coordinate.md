# ADR 0012: Catalog Terminal DLT Input by Actual Coordinate

- Status: Accepted
- Date: 2026-07-15

## Context

The DLT catalog required valid original topic, partition, and offset headers before it could store evidence. Missing, repeated, malformed, or unsupported headers threw into an error handler that intentionally did not acknowledge database failures. A permanently malformed record could therefore retry forever and starve later records on its partition. Inventing original coordinates or storing raw poison bytes would corrupt evidence or increase disclosure risk.

## Decision

Classify invalid original routing, empty/oversized payloads, and invalid event contracts as terminal data failures. Persist one immutable `terminal_dlt_records` row keyed by consumer name and the actual consumed DLT topic, partition, and offset. Store only SHA-256 hashes, byte sizes, allowlisted bounded headers, a stable failure code, a fixed sanitized summary, and observation time. Do not store the raw key/payload, invalid routing values, stack traces, or exception messages.

Only acknowledge the DLT record after terminal evidence commits. A database failure remains exceptional and retains the Kafka offset for redelivery. Unique actual coordinates make redelivery idempotent and conflicting evidence fail closed. Do not add a quarantine topic in this milestone.

## Consequences

- Permanent data defects advance after durable sanitized evidence and cannot indefinitely starve their partition.
- Temporary evidence-store failure still pauses the partition, as required to avoid silent loss, and recovers by redelivery.
- Operators inspect terminal intake separately from replayable `dead_letter_records`; terminal evidence is never replayable.
- Metrics and alerts distinguish recorded, duplicate, and persistence-failure outcomes without coordinate or error-text labels.
- A restricted quarantine topic remains a future option only with a separate ACL, retention, privacy, and operational design.

## Alternatives considered

- Acknowledging malformed input without durable evidence was rejected as silent loss.
- Retrying malformed headers indefinitely was rejected because no dependency recovery can repair record bytes.
- Synthesizing original coordinates was rejected because it makes audit evidence false.
- Copying raw input to PostgreSQL was rejected because it can preserve secrets, oversized data, or parser exploits beyond Kafka's controlled retention.
