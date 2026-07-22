# ADR 0001: Record Architecture Decisions

- Status: Accepted
- Date: 2026-07-11
- Decision owners: LedgerFlow maintainers

## Context

LedgerFlow is intended to be a production-grade system developed through multiple milestones. Significant technical choices will accumulate around module boundaries, persistence, API compatibility, security, operations, and dependencies.

Commit messages, pull-request discussion, and chat or pairing-session history do not provide a reliable long-term explanation of why a decision was made. Architecture documentation describes the current intended system, but without a decision record it can lose the alternatives, constraints, and consequences that produced that design.

The project needs a lightweight, version-controlled mechanism for durable architectural decisions without requiring an ADR for routine implementation details.

## Decision

LedgerFlow will record significant architecture decisions as Markdown Architecture Decision Records under `docs/adr`.

ADR filenames use a sequential four-digit number and a short kebab-case title:

```text
docs/adr/0002-short-decision-title.md
```

Every ADR contains:

- title and number;
- status;
- date;
- decision owners;
- context;
- decision;
- consequences; and
- alternatives considered.

Allowed statuses are:

- `Proposed`: under discussion and not approved for implementation;
- `Accepted`: approved and currently authoritative;
- `Deprecated`: retained for history but no longer recommended; and
- `Superseded`: replaced by another ADR, which must be linked.

Accepted ADRs are historical records. Do not rewrite them to make a later decision appear original. Correct typographical errors without changing meaning; otherwise create a new ADR and mark the old one superseded or deprecated.

An ADR is required for decisions that materially affect one or more of:

- feature-module boundaries or dependency direction;
- data ownership or cross-module database access;
- public API compatibility or versioning policy;
- authentication, authorization, secrets, or security boundaries;
- persistence technology or production infrastructure;
- deployment topology or asynchronous communication;
- system-wide handling of money, time, retries, or consistency;
- a lasting exception to an architecture rule; or
- a difficult-to-reverse production dependency or technology choice.

An ADR is not required for routine feature implementation, local refactoring that preserves boundaries and behavior, test additions, or reversible implementation details already covered by accepted guidance.

Proposed ADRs are reviewed with the associated ExecPlan before implementation. Maintainer approval changes their status to `Accepted`. Code, tests, and architecture documentation must reference or reflect accepted decisions where relevant.

This ADR records the ADR process itself and is accepted when the initial governance proposal is approved.

## Consequences

### Positive

- Important decisions and their rationale remain available in the repository.
- Future changes can distinguish intentional architecture from accidental structure.
- Exceptions and reversals are explicit and reviewable.
- Future planning can rely on durable context rather than prior conversations.

### Costs

- Significant decisions require an additional short document and review.
- ADR status and related architecture documentation must be maintained.
- Maintainers must exercise judgment about which choices are significant enough to record.

## Alternatives considered

### Rely on architecture documentation only

Rejected because architecture documentation describes the current state but does not reliably preserve alternatives, rationale, or superseded decisions.

### Rely on issues, pull requests, or commit messages

Rejected because those records may be scattered or unavailable later, mix decisions with implementation discussion, and are harder to discover from the affected architecture.

### Require an ADR for every technical choice

Rejected because it would create excessive process and obscure consequential decisions among routine implementation details.
