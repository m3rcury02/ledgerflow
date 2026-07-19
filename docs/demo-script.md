# Demo Video Script

A ~10-minute walkthrough script for a recorded portfolio demo. Every command below is a real,
already-validated command from this repository — `README.md`, the `Makefile`, or a milestone doc
— not staged for the recording. Screen-record a terminal plus, where noted, a browser tab; see
[`docs/screenshots-guide.md`](screenshots-guide.md) for what to also capture as static images.

Run this from a clean checkout so the audience sees exactly what a technical interviewer would
get by cloning the repository — don't reuse a long-running dev environment with extra local
state.

## Scene 1 — What this is (30s, talking head or title card)

"LedgerFlow is a Java 25 / Spring Boot 4.1 payments-and-ledger modular monolith, plus six
portfolio extensions on top of it: CI/CD, performance/failure testing, hardened containers, a
local Kubernetes deployment, a validated-but-never-applied AWS Terraform design, and an optional
AI operations assistant. Everything I show is a real command against a real local environment —
nothing here is a slide claiming something works."

## Scene 2 — The one-command proof (90s)

```bash
make smoke-test
```

Narrate while it runs: PostgreSQL and Kafka Testcontainers spin up, the app exercises
authenticated HTTP, exact replay vs. conflict, provider authorize/capture, balanced ledger
posting, transactional outbox, at-least-once publish/consume, and exactly one semantic
notification effect — no shared local data or credentials required. Point out the pass/fail
summary at the end.

Reference: `README.md`, "Technical interview quick start".

## Scene 3 — Full verification gate (60s, can be sped up / cut in editing)

```bash
./gradlew --no-daemon clean verify --console=plain
```

Narrate: formatting, static analysis, unit tests, PostgreSQL Testcontainers repository/HTTP/
concurrency tests, Spring Modulith + ArchUnit module-boundary rules, OpenAPI validation, and
documentation checks, all in one gate. This is the same gate every PR runs in CI.

## Scene 4 — Create a real order and read the ledger (2 min)

Start dependencies and the app (or cut to a pre-started environment for time — narrate that this
is `docker compose` plus `./gradlew :application:bootRun`, both shown in full in `README.md`).
With a customer JWT from the local Keycloak realm:

```bash
curl --fail-with-body http://localhost:8080/api/v1/orders \
  --request POST \
  --header "Authorization: Bearer ${LEDGERFLOW_ACCESS_TOKEN}" \
  --header 'Content-Type: application/json' \
  --header 'Idempotency-Key: checkout-order-0001' \
  --header 'X-Correlation-Id: checkout-demo-001' \
  --data '{"clientReference":"checkout-0001","amount":{"amountMinor":259900,"currency":"INR"},"paymentMethodReference":"pm_mock_success"}'
```

Show the `201` response: order `COMPLETED`, payment `CAPTURED`. Then re-run the exact same
request — narrate the `Idempotency-Replayed: true` header and identical body, then show a `409`
by changing one field with the same key. Finally, run the read-only ledger SQL
(`docs/sql/ledger-queries.sql`) to show the balanced journal this one order actually posted —
this is the moment that makes "immutable balanced double-entry ledger" concrete instead of a
claim.

Reference: `README.md`, "Create Order API" and "Capture accounting and Kafka slice".

## Scene 5 — Observability: one trace, three tools (90s)

```bash
make demo-observability
```

Narrate: the demonstration prints the durable business result first, then its trace and
correlation IDs, then verifies the same trace really exists in Tempo and the correlated logs
really exist in Loki — cut to the Grafana browser tab and click through the Prometheus→Tempo
exemplar and Tempo→Loki trace link live, since that's the part a screenshot can't show as
convincingly as watching the click happen.

Reference: `README.md`, "Observability demonstration"; `docs/observability.md`.

## Scene 6 — Container hardening (60s)

```bash
docker build --tag ledgerflow:local .
docker inspect --format '{{.Config.User}}' ledgerflow:local
```

With the dependency stack up (`scripts/dev-up`), start the container under the real hardening
flags and show a clean `Started LedgerFlowApplication` with no filesystem errors — this is the
exact invocation `docs/container-hardening.md` used, environment variables included, not a
shortened stand-in:

```bash
docker run --rm --network host --read-only --tmpfs /tmp:rw,size=64m \
  --env LEDGERFLOW_DB_URL=jdbc:postgresql://localhost:5433/ledgerflow \
  --env LEDGERFLOW_DB_USERNAME=ledgerflow \
  --env LEDGERFLOW_DB_PASSWORD=change-me-local-postgres \
  --env LEDGERFLOW_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
  --env LEDGERFLOW_OAUTH2_AUDIENCE=ledgerflow-api \
  --env LEDGERFLOW_OAUTH2_ISSUER=http://localhost:8081/realms/ledgerflow \
  --env LEDGERFLOW_OAUTH2_JWK_SET_URI=http://localhost:8081/realms/ledgerflow/protocol/openid-connect/certs \
  --env LEDGERFLOW_MANAGEMENT_PORT=8082 \
  ledgerflow:local
```

Narrate: non-root user, a container that starts and serves traffic with *no writable root
filesystem at all* — the only writable path is a `tmpfs` mount, and nothing in the application
needs more than that. Cut to the doc for the base-image scan that found 5 HIGH-severity CVEs and
closed them by removing unused font-rendering/PKCS#11 package chains, re-verified with a real
order still working against the hardened image.

Reference: `docs/container-hardening.md`.

## Scene 7 — Local Kubernetes, live scaling (2 min, the visual highlight)

```bash
scripts/kind-up
scripts/kind-smoke-test
```

If time allows, cut to a pre-recorded clip of the real HPA scale-out
(`kubectl get hpa -w` during the load burst) — 2→4 `ledgerflow-api` replicas in 27 seconds at
89% CPU, `ledgerflow-worker` staying fixed at 2 the whole time because it's a background-only
Deployment with no ingress route at all. This is the single most visually compelling piece of
evidence in the whole demo: a real autoscaler reacting to real load, not a diagram.

Reference: `docs/kubernetes-deployment.md`.

## Scene 8 — AWS Terraform: designed, validated, never applied (60s)

```bash
cd deploy/terraform/aws
terraform fmt -check -recursive .
terraform init -backend=false && terraform validate
tflint
checkov -d . --compact
```

Narrate clearly: this never runs `terraform apply` and never touches a real AWS account — say
this explicitly on camera, since it's the one part of the demo that could otherwise look like a
real cloud deployment. Cut to the architecture diagram in `docs/aws-terraform-design.md` for the
two-AZ topology and the manually-computed cost estimate.

## Scene 9 — Optional AI operations assistant (90s)

```bash
cd ai-assistant
.venv/bin/python -m pytest -v
```

Narrate: 69 tests, deterministic fake provider by default (no API key, no network, no cost).
Then a live `curl` against the running FastAPI service:

```bash
curl -s http://localhost:8000/v1/incidents/summarize \
  --header 'Content-Type: application/json' \
  --data '{"alert_name": "LedgerFlowOutboxBacklog", "description": "Outbox oldest age climbing past 60s"}' | jq
```

Show the structured response — summary, evidence, confidence, cited runbook — and narrate the
one property worth dwelling on: this assistant never claims to have performed remediation, and a
secret embedded in telemetry never reaches a real model, which `tests/test_openai_provider_secrets_never_sent.py`
proves by intercepting the actual outbound HTTP request.

Reference: `docs/ai-operations-assistant.md`.

## Scene 10 — Close (30s)

"Every claim in this demo traces to a command you just watched run, or a doc with the real
output already in it — not a slide. The repository, all the design docs, and this evidence are
at [repo URL]." Point at `docs/residual-risks.md` and `docs/operational-limitations.md` on
screen for one beat — closing on "here's what this explicitly doesn't prove" is a stronger
signal of engineering judgment than closing on the highlight reel alone.

## Notes for whoever records this

- Total run time target: ~10 minutes. Scenes 4 and 7 are the two worth spending real time on;
  everything else can be tightened in editing.
- Every command above was, at the time this script was written, a command already exercised for
  real by this repository's own milestone validation — this script does not introduce a single
  new command path. If a command's output ever stops matching what's narrated here, that's a
  signal the script (or the repository) has drifted and needs a re-check, not a re-record with
  adjusted narration.
