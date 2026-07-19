# Interview Discussion Guide

Anticipated questions, organized by topic, with grounded answers and a pointer to the primary
evidence for each — so an answer in conversation can go one level deeper than what's written
here if asked. The last section, "Hard questions," is deliberately the most important one: how
this project's weaknesses are discussed is a stronger signal than how its strengths are.

## Architecture

**Why a modular monolith instead of microservices?** Feature modules (`orders`, `payments`,
`ledger`, `messaging`, `notifications`, `operations`) with narrow APIs and one deployable
artifact, boundaries enforced by Spring Modulith and ArchUnit — not a promise, a build-time
check. The reasoning: a modular monolith keeps transactional simplicity where it's genuinely
valuable (the ledger write) and defers the cost of network boundaries, distributed transactions,
and independent deployability until real evidence — traffic pattern, team boundary, scaling
need — justifies paying for it. See [ADR 0002](adr/0002-mvp-module-boundaries-and-orchestration.md).

**What would force you to actually split it?** A module whose write volume or latency profile
genuinely diverges from the rest (most likely `notifications`, since it's already
Kafka-consumer-shaped and has no synchronous caller), or an org boundary — a separate team owning
one module cleanly. Neither exists yet in a solo portfolio project, so splitting now would be
premature.

**Why no distributed transaction between the ledger write and the Kafka publish?** A
transactional outbox: the ledger row and an outbox row commit in the same local database
transaction, then a separate leased publisher reads outbox rows and publishes to Kafka
out-of-band, marking them published only after broker acknowledgement. This gets real
at-least-once delivery without a two-phase-commit dependency on Kafka being reachable at write
time. See [ADR 0006](adr/0006-transactional-outbox-and-at-least-once-kafka.md).

## Data integrity

**How do you know the ledger can't go unbalanced?** A PostgreSQL trigger, not application code,
rejects an unbalanced or mutated journal at the database layer — so even a bug in application
logic, or a direct SQL statement bypassing the application entirely, can't produce an unbalanced
journal. Corrections are append-only. See [ADR 0005](adr/0005-immutable-balanced-double-entry-ledger.md).

**What happens if the same request is sent twice?** A scoped idempotency key plus a request-body
hash: the identical key with an identical body returns the original result
(`Idempotency-Replayed: true`); the identical key with a *changed* field — including an opaque
payment reference — returns `409`. This is proven with a real repeated HTTP request in the demo,
not just asserted. See `docs/api-design.md`, ADR 0003 (superseded in part by ADR 0013 — see
below).

**Why does ADR 0013 supersede part of ADR 0003?** The original design sequenced the public order
workflow as one short local transaction. Building the actual provider-backed workflow surfaced
that this didn't hold up: the provider call can't happen inside that transaction (see the
payment-provider boundary question below), so finalization needed to become explicitly
recoverable rather than atomic-by-construction. Recorded as a new ADR that states what changed
and why, not a silent rewrite of the original. See
[ADR 0013](adr/0013-finalize-the-public-order-workflow-with-recoverable-local-transactions.md).

## Payment provider integration

**Why no transaction held open across the provider HTTP call?** An external call can be slow,
time out, or return an ambiguous result — holding a database transaction open across it would
turn a network problem into a database lock contention problem. Instead: a stable operation ID
identifies each provider attempt, and an unknown/timeout outcome is resolved by looking that ID
up against the provider afterward, never by blindly retrying with a new ID. See
[ADR 0004](adr/0004-payment-provider-boundary-and-state-machine.md).

**Is the payment provider real?** No — a deterministic mock HTTP service with a documented
contract (`application/src/testFixtures/openapi/mock-payment-provider.yaml`), used specifically
so failure modes (timeout, decline, unknown outcome) are exercisable on demand and
deterministically, not left to chance against a real sandbox. Stated as a limitation, not
implied away: see [`docs/operational-limitations.md`](operational-limitations.md), "A real
payment provider requires a new threat review, token-storage decision, provider-specific
reconciliation, and PCI assessment."

## Resilience and failure testing

**What failure modes have you actually tested, not just designed for?** 11 real experiments
against Testcontainers-backed PostgreSQL/Kafka — provider timeout, mid-transaction process
crash and recovery, concurrent database-lock contention, a telemetry-backend outage that must
never affect a financial transaction, and more — with real recorded numbers, not estimates. Two
scenarios required rebuilding the experiment design mid-milestone when the first design didn't
hold up under real infrastructure behavior (Postgres never blocking a plain `SELECT` behind a
writer's row lock was one surprise) — see
[`docs/performance-experiments.md`](performance-experiments.md).

**What's your graceful-shutdown story?** Measured, not assumed: 154.4ms from real `SIGTERM` log
timestamps across two full clean startup/shutdown cycles, confirming in-flight work drains
before the process exits. See [`docs/container-hardening.md`](container-hardening.md).

## Security

**Walk me through the threat model.** `docs/threat-model.md` covers BOLA (broken object-level
authorization — an operator token alone cannot read a customer's order), replay, strict input
validation, secrets handling, Kafka/outbox safety, telemetry safety, and privileged operator
recovery. Residual risks are tracked explicitly, not hidden — see "Hard questions" below.

**How is the operator recovery API secured, given how powerful it is?** Separate read/retry/
break-glass permission scopes, JWT-derived actor identity (not a request parameter), a mandatory
human-readable reason on every privileged action, cooldowns and caps, one-use approval for the
most sensitive path, and immutable audit evidence for every action taken. See
[ADR 0008](adr/0008-secured-operator-recovery.md).

**What does your CI security gate actually check, and what will it never accept?**
`scripts/security-scan` (Trivy-based) scans repository secrets, packaged Java dependencies, and
every Compose service image. Repository-secret and packaged-application findings have *zero*
exception path — they always fail the gate. Only narrowly-scoped, digest-bound, expiring,
documented exceptions exist for local-development-only container images, tracked in a risk
register with explicit expiry dates. See [`docs/security/`](security/).

## DevOps, containers, Kubernetes

**What does your CI pipeline actually run?** `ci.yml` (full `./gradlew clean verify`, then an
OCI image build with a CycloneDX SBOM and a Trivy image scan) and `codeql.yml` on every PR;
`security-scan.yml` (the Docker-socket-privileged gate) on push-to-`main` and schedule only —
deliberately not on PRs, so a fork-originated PR is never granted Docker-socket access. Every
third-party GitHub Action is pinned to a full commit SHA. See "Hard questions" for the honest
caveat about this pipeline's real-world exercise so far.

**What did deploying to a real Kubernetes cluster teach you that manifest review didn't?** Four
issues that `helm lint`/`helm template` couldn't have caught because neither executes anything:
a Kafka controller-quorum self-registration deadlock through its own Service, under-thresholded
liveness probes on three dependencies, a `tcpSocket` probe on a loopback-only sidecar that could
never pass, and a Keycloak token-issuer mismatch between port-forwarded and in-cluster access.
All four are root-caused, not just patched, in
[`docs/plans/portfolio-extension-execplan.md`](plans/portfolio-extension-execplan.md), Milestone
4.

**Show me the autoscaler actually working.** A real 150-second, 40-way-concurrent load burst
against the API's liveness endpoint drove CPU to 89%/50% and the HPA scaled 2→4 replicas within
27 seconds — real `metrics-server` v0.9.0 numbers, not `<unknown>`. The worker Deployment stayed
fixed at 2 replicas the entire time, because it has no ALB target, no autoscaling target, and no
inbound `NetworkPolicy` rule of any kind. See
[`docs/kubernetes-deployment.md`](kubernetes-deployment.md).

## Cloud infrastructure-as-code

**Why design AWS infrastructure you never deploy?** The extension's own scope excludes running
`terraform apply` or touching a real account — the value being demonstrated is the *design and
validation* discipline (structure, least-privilege IAM, security posture, cost modeling), which
is checkable without a cloud bill or credentials. This is stated explicitly wherever the design
is referenced, not left implicit.

**What did static validation miss?** Three real runtime defects, all found by manual design
review, not by any of the five passing tools: an ALB health-check port gap that would have hung
every service rollout indefinitely, a `tmpfs` configuration Fargate doesn't support at all (EC2
launch type only), and datasource environment variables that didn't match what the application
actually reads. This is the single best example in the whole repository of "static validation
passing" not meaning "correct" — see
[`docs/aws-terraform-design.md`](aws-terraform-design.md), "A note on what 'validated' does and
doesn't mean here."

## AI operations assistant

**Why does it default to a fake provider instead of a real LLM?** So running it, and every
automated test, costs nothing and needs no API key or network access by default — a real
provider is one environment variable and a maintainer-supplied key away, never a hidden default
cost. See [`docs/ai-operations-assistant.md`](ai-operations-assistant.md), "Design decisions."

**How do you know it can't leak a secret to OpenAI?** A test that intercepts the actual HTTP
transport the OpenAI SDK builds (`httpx.MockTransport`) and asserts on the real outbound request
body — not the fake provider, which makes no network call and would make the assertion vacuous.
It proves a secret embedded in raw telemetry never appears in what's sent; only its
`[REDACTED:...]` placeholder does. See
`ai-assistant/tests/test_openai_provider_secrets_never_sent.py`.

**How do you know it resists prompt injection?** Only partially, and that's stated explicitly.
Structural isolation — untrusted telemetry can only land in one clearly delimited prompt
section — is tested directly. Behavioral compliance from a live model under adversarial input is
not something any automated test in this repository can prove, and the docs say so rather than
imply broader coverage than exists. See
[`docs/ai-operations-assistant.md`](ai-operations-assistant.md), "Prompt-injection resistance"
and "Evaluation fixtures."

## Hard questions

**What's the weakest part of this project?** Two honest answers. First: the CI pipeline itself
has limited real-world exercise — `security-scan.yml` has never run in GitHub Actions against
this branch, because it only triggers on push-to-`main` or schedule, and this branch hasn't been
merged (see `docs/plans/portfolio-extension-execplan.md`, Milestone 7). Second: the AWS Terraform
design has never been applied, so however careful the review, there's a real chance a `plan`/
`apply` would surface something the static tools and manual review both missed — the three
defects already found are evidence that this class of gap is real, not hypothetical.

**What would you not trust in production as-is?** Everything the residual-risk registers say not
to trust — that's the point of writing them down rather than only writing down what works. Read
[`docs/residual-risks.md`](residual-risks.md) for the full list; the two most consequential are
single-node PostgreSQL/Kafka with no backup/replication/DR story, and per-instance (not
cluster-wide) rate limiting and circuit-breaker state.

**What's fake or simulated here, explicitly?** The payment provider (a deterministic mock HTTP
service, by design, so failure modes are reproducible on demand). The AWS deployment (designed
and statically validated, never applied — no real AWS account touched). The AI assistant's
default provider (deterministic, templates off retrieved content, makes no model call at all).
Everything else — the database, the message broker, the Kubernetes cluster, the container
scans, the load test, the CI checks that *have* run — is real, not mocked or simulated.

**If you had another two weeks, what would you build next?** A live OpenAI smoke test for the AI
assistant (currently deferred pending a maintainer-supplied key — the offline suite can't prove
the real API accepts the exact schema/parameter combination used); opening the PR this branch
needs to get real `ci.yml`/`codeql.yml` evidence instead of only local reproduction; and, if
genuinely pursuing a production path, closing the items in `docs/security/mvp-residual-risk-register.md`
one at a time, starting with backup/restore evidence for PostgreSQL, since that's the
highest-severity gap with the least work already done toward closing it.
