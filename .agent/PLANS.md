# LedgerFlow Execution Plans

An ExecPlan is a self-contained, living implementation document. It must let an engineer unfamiliar with prior chat history complete and verify the approved work using only the repository and the plan.

## When an ExecPlan is required

Use an ExecPlan when work has any of these characteristics:

- more than one independently verifiable implementation step;
- changes across multiple modules or external boundaries;
- a database migration or data transition;
- a new or changed public HTTP contract;
- a new production dependency;
- security, authorization, money, idempotency, or compatibility risk;
- architecture or deployment changes;
- uncertainty that requires investigation or prototyping; or
- work likely to span multiple sessions.

A localized documentation correction, test correction, or small implementation change may use the direct user request as its approved milestone. If scope or risk grows, stop and create an ExecPlan.

## Approval and milestone discipline

- Store ExecPlans under `.agent/plans/YYYY-MM-DD-<short-name>.md`.
- Each plan has a status: `Proposed`, `Approved`, `In Progress`, `Blocked`, or `Complete`.
- An agent may draft or revise a plan but may not infer or grant its approval.
- Approval must be explicit from a maintainer and recorded in the plan with the approver and date.
- A plan may describe later milestones, but only one milestone may be marked `Approved` or `In Progress`.
- Do not implement proposed later milestones while completing the current milestone.
- Read-only investigation may support planning, but it must not silently become implementation.
- If scope, public interfaces, data handling, or acceptance criteria change materially, pause implementation, revise the plan, and obtain approval for the revision.

## Plan quality requirements

Every ExecPlan must:

- describe the user-visible or operational outcome, not merely code edits;
- use repository-relative paths and name relevant symbols or components;
- explain the current state discovered in the repository;
- state what is in scope and what is deliberately excluded;
- describe API, persistence, module-boundary, and dependency changes where relevant;
- identify failure modes, compatibility concerns, and recovery steps;
- provide exact commands and observable expected results;
- distinguish facts discovered in the repository from assumptions;
- remain current as work proceeds; and
- avoid depending on external chat history or unstated decisions.

Do not prescribe broad cleanup. List incidental changes only when they are necessary to achieve the approved outcome.

## Required ExecPlan structure

Use this structure:

### Title

A concise outcome-oriented title.

### Metadata

- Status:
- Owner:
- Created:
- Last updated:
- Approved by:
- Approval date:
- Current milestone:

### Purpose and outcome

Explain why the work matters and what a user, operator, or developer will be able to observe when it is complete.

### Current state

Record the relevant implementation, contracts, tests, configuration, and constraints found in the repository. Include repository-relative paths.

### Scope and non-goals

State exactly what will change and what will not change.

### Interfaces and data

Describe affected OpenAPI operations, Java interfaces or types, module interactions, database migrations, configuration, and compatibility behavior. Write “None” where a category is unaffected.

### Milestones

For every milestone include:

- status (`Proposed`, `Approved`, `In Progress`, or `Complete`);
- intended outcome;
- implementation work;
- validation commands; and
- observable acceptance criteria.

Only the current milestone may be approved or in progress.

### Implementation approach

Describe the intended sequence and the reasons for non-obvious choices. Include dependency justification and alternatives when a production dependency is proposed.

### Validation and acceptance

List exact commands, required environment such as Docker, expected successful results, and relevant manual checks. The final milestone must include `./gradlew clean verify`.

### Rollback and recovery

Explain how to recover from partial execution or deployment. Database recovery must use forward corrective migrations; never propose editing an already merged migration.

### Progress

Maintain timestamped checkboxes:

- [ ] `YYYY-MM-DD HH:MMZ` — work item and result

Update this section whenever work pauses or a milestone changes state.

### Surprises and discoveries

Record unexpected repository facts, failed assumptions, and evidence such as test output or relevant paths. Keep this factual and concise.

### Decision log

For each implementation decision record:

- date;
- decision;
- rationale; and
- whether an ADR is required.

### Outcome and follow-up

At completion, summarize delivered behavior, verification evidence, known limitations, and separately proposed follow-up work. Follow-up work is not automatically approved.

## Executing a plan

1. Re-read the plan and confirm the current milestone is approved.
2. Inspect the working tree and preserve unrelated changes.
3. Mark only the approved milestone `In Progress`.
4. Implement in small, verifiable increments.
5. Update progress, discoveries, and decisions as facts change.
6. Run focused checks during development and the complete verification lifecycle before completion.
7. Compare the result with every acceptance criterion.
8. Mark the milestone complete only after its checks pass.
9. Do not begin the next milestone without separate approval.

A blocked plan must state the concrete blocker, the evidence gathered, and the exact decision or external change needed. Difficulty or an incomplete investigation is not by itself a blocker.
