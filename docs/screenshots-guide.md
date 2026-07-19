# Screenshots Guide

This is a guide for what to capture, not a set of captured screenshots — this repository's
tooling runs in a headless sandbox with no display, so no image in this repository was actually
taken from a running UI. Follow this guide on a real workstation with the local environment up
(`docker compose --env-file .env.example up -d`, then whichever milestone's stack it names) to
produce the actual images for a portfolio page or the demo video's B-roll.

For each shot: capture the whole browser window or terminal, not a cropped fragment — an
interviewer should be able to see the URL bar or shell prompt and believe it's real.

## 1. Grafana — the five provisioned dashboards

- Navigate to `http://localhost:3000`, log in (no password on the local realm's admin — see
  `docs/observability.md` for exact access), open the dashboard list.
- Capture: the dashboard list itself (proves "five provisioned dashboards" isn't a claim), then
  one full-resolution shot of the request-rate/latency panel mid-`make demo-observability` run so
  a real spike is visible, not a flat idle line.

## 2. Grafana — a real cross-tool trace link

- From a Prometheus panel showing a latency spike, click the exemplar link into Tempo; from that
  trace, click a span's "Logs for this span" link into Loki.
- Capture: three sequential screenshots (Prometheus panel → Tempo trace view → Loki log lines),
  or a short screen recording — this sequence is much more convincing as motion than as a static
  image, since the point is that the links actually work, not that the tools exist.

## 3. Kubernetes — the HPA scaling live

- During `scripts/kind-smoke-test`'s load burst, run `kubectl get hpa -w` and
  `kubectl get pods -l app.kubernetes.io/component=api -w` in split terminal panes.
- Capture: a screen recording (not a still) of the replica count changing 2→4, with the HPA's
  `TARGETS` column showing the real CPU percentage. This is the single most visually compelling
  piece of evidence in the whole repository — a static screenshot loses most of its value.

## 4. Kubernetes — pod security context proof

- `kubectl get pod <ledgerflow-api-pod> -o jsonpath='{.spec.securityContext}{"\n"}{.spec.containers[0].securityContext}'`
- Capture: the terminal output showing `runAsNonRoot`, `readOnlyRootFilesystem: true`,
  `capabilities: {drop: [ALL]}` — a claim in `docs/kubernetes-deployment.md` made visibly real.

## 5. A real order, start to finish

- One `curl` creating an order (`README.md`, "Create Order API"), then the same request repeated
  showing `Idempotency-Replayed: true`, then a `409` from a changed field with the same key.
- Capture: three terminal screenshots or one scrolling terminal recording — sequence matters more
  than any single frame here.

## 6. The ledger, in SQL

- Run the balance/history query from `docs/sql/ledger-queries.sql` against the order just created.
- Capture: the query and its result together — a balanced debit/credit pair from one real order,
  not a diagram of what a ledger entry "would look like."

## 7. CI, green, on a real PR

- Once a PR exists for this branch (see the maintainer decision recorded in
  `docs/plans/portfolio-extension-execplan.md`'s Milestone 7 entry), capture the GitHub Actions
  checks list showing `ci.yml` and `codeql.yml` passing on the actual PR, not a local terminal
  substitute — this is the one piece of evidence that only exists on GitHub's UI.

## 8. AWS Terraform — the plan-free validation trio

- Terminal capture of `terraform validate`, `tflint`, and `checkov -d . --compact` output in one
  scrolling shot, ending on the `checkov` summary line (`0 failed`). Caption it explicitly as
  "validated, never applied" wherever it's used, so it can't be mistaken for a live deployment.

## 9. The AI assistant, structured output

- A `curl` against `POST /v1/incidents/summarize` (see `docs/ai-operations-assistant.md`,
  "Running the server") piped through `jq`, showing the full structured response — summary,
  evidence, confidence, cited runbook — for one realistic incident description.

## What not to stage

Don't screenshot a state that required special setup unrelated to what's documented (seeded fake
traffic history, a manually edited dashboard panel, a hand-crafted response the API wouldn't
normally return). Every image should be reproducible by someone else following the exact command
in the doc it illustrates.
