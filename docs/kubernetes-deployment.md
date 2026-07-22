# Local Kubernetes Deployment

Every claim below was checked with a real command against a real `kind` cluster on 2026-07-18 —
nothing here is asserted from the
manifests' contents alone. Several of the fixes below only surfaced by actually deploying;
they are recorded rather than smoothed over, per this repository's existing evidence style
(see `docs/container-hardening.md`).

## Architecture

One image (`ledgerflow:local`, Milestone 3), two role-differentiated Deployments:

| Deployment | Replicas | Config | Public Service | Autoscaled |
| --- | --- | --- | --- | --- |
| `ledgerflow-api` | 2 (HPA: 2–5) | all four background-job flags `false` | yes (ClusterIP) | yes |
| `ledgerflow-worker` | 2 | all four background-job flags `true` | **no** | no |

"Worker" is the same image as "API," never a second Dockerfile — see
`docs/container-hardening.md`. Splitting the roles keeps autoscaling the request path from
multiplying lease contention against the outbox/notification/recovery tables the worker owns.

Dependencies (PostgreSQL, Kafka KRaft, Keycloak) run as minimal, dev-only plain Kubernetes
manifests in the same cluster and namespace (`deploy/kind/dependencies/`) rather than
bridging the `kind` node's Docker network to the host's `scripts/dev-up` Compose stack — a
self-contained cluster is reproducible on any machine with `kind` installed. Valkey and the
observability stack are intentionally not deployed here: the application has no cache integration,
and OTLP export failure
is fire-and-forget and never fails a request.

## One-command deploy

```bash
scripts/kind-up
```

Creates (or reuses) the `ledgerflow-dev` kind cluster, builds and loads both local images
(`ledgerflow:local`, `ledgerflow-mock-provider:local`), applies the dependencies, waits for
them, then `helm upgrade --install`s the chart and waits for its rollout. Real final state
from this milestone's run:

```
NAME                READY   UP-TO-DATE   AVAILABLE
kafka               1/1     1            1
keycloak            1/1     1            1
ledgerflow-api      4/4     4            4   (scaled from 2 by the HPA load test below)
ledgerflow-worker   2/2     2            2
postgres            1/1     1            1
```

## Real end-to-end proof

```bash
scripts/kind-smoke-test
```

Real output from this milestone's run:

```
[kind-smoke-test] Confirming ledgerflow-api rollout is healthy...
deployment "ledgerflow-api" successfully rolled out
deployment "ledgerflow-worker" successfully rolled out
[kind-smoke-test] Provisioning the smoke-test client (idempotent)...
[kind-smoke-test] Minting an access token...
[kind-smoke-test] Creating a real order through the port-forwarded API...
[kind-smoke-test] Order created: status=COMPLETED http=201
[kind-smoke-test] Polling outbox_events for correlation_id=... until the worker Deployment publishes it...
[kind-smoke-test] Outbox event PUBLISHED — confirmed the worker Deployment, not the API Deployment, drove this (API has LEDGERFLOW_OUTBOX_PUBLISHER_ENABLED=false).
[kind-smoke-test] Confirming NetworkPolicy refuses a direct request to a worker pod's HTTP port...
[kind-smoke-test] Confirmed: direct HTTP access to the worker pod is refused.
[kind-smoke-test] PASS: real order created (201/COMPLETED), worker Deployment published its outbox event independently, worker NetworkPolicy verified.
```

This proves, with real infrastructure, not just manifest inspection: a real order reaches
`201`/`COMPLETED` through the port-forwarded `ledgerflow-api` Service; the outbox row only
reaches `PUBLISHED` because `ledgerflow-worker` (a separate Deployment, with its own
`LEDGERFLOW_OUTBOX_PUBLISHER_ENABLED=true`) processed it — the API Deployment cannot have,
since that flag is `false` there; and the worker `NetworkPolicy` genuinely refuses inbound
HTTP, not merely "no Service points at it."

## Security posture (real, inspected)

```bash
kubectl --namespace ledgerflow get pod -l app.kubernetes.io/component=api \
  -o jsonpath='{.items[0].spec.securityContext}{"\n"}{.items[0].spec.containers[0].securityContext}'
```

```json
{"fsGroup":101,"runAsGroup":101,"runAsNonRoot":true,"runAsUser":100,"seccompProfile":{"type":"RuntimeDefault"}}
{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]},"readOnlyRootFilesystem":true}
```

`runAsUser: 100` / `runAsGroup: 101` are not guessed — they are `ledgerflow:local`'s real
non-root UID/GID, confirmed with `docker run --rm --entrypoint id ledgerflow:local` before
being written into the chart (Alpine's `adduser -S` allocates system UIDs starting at 100,
not the commonly-assumed 1000). `readOnlyRootFilesystem: true` is satisfied by an `emptyDir`
mounted at `/tmp`, matching `docs/container-hardening.md`'s recommended production flags.

ServiceAccounts are least-privilege by construction, not by a narrowly scoped Role:

```bash
kubectl --namespace ledgerflow get serviceaccount ledgerflow-api ledgerflow-worker \
  -o jsonpath='{range .items[*]}{.metadata.name}{" automount="}{.automountServiceAccountToken}{"\n"}{end}'
# ledgerflow-api automount=false
# ledgerflow-worker automount=false
```

Neither has any RBAC `Role`/`RoleBinding` — the application never calls the Kubernetes API,
so the correct minimum is zero API access.

## HorizontalPodAutoscaler (real, under real load)

`kind` ships no metrics API by default; `deploy/kind/dependencies/metrics-server.yaml` pins
`kubernetes-sigs/metrics-server` `v0.9.0` (confirmed current via the GitHub releases API) with
the standard `--kubelet-insecure-tls` patch `kind` requires. With that running, the HPA reports
real CPU, not `<unknown>`, and reacts to real load. 40 concurrent loops of unauthenticated
`GET /actuator/health/liveness` against the port-forwarded `ledgerflow-api` Service for 150s
produced:

```
[  7s] TARGETS 89%/50%   REPLICAS 2
[ 27s] TARGETS 74%/50%   REPLICAS 4
[ 44s] TARGETS 78%/50%   REPLICAS 4
[ 80s] TARGETS 60%/50%   REPLICAS 4
[ 99s] TARGETS 48%/50%   REPLICAS 4
[152s] TARGETS 25%/50%   REPLICAS 4
```

Real scale-out from 2 to 4 replicas within 27 seconds of crossing the 50% target. Replicas
stayed at 4 for several minutes after load dropped below target — Kubernetes' default
`scaleDown.stabilizationWindowSeconds` (300s) — not a bug; a slower deliberate scale-down is
the documented default HPA behavior, favoring stability over flapping.

## Validation

```bash
helm lint deploy/helm/ledgerflow            # 0 charts failed
helm template deploy/helm/ledgerflow        # renders 12 resources, no errors
```

## Real issues found and fixed while deploying (not merely inspecting manifests)

- **Dependency liveness probes killed pods before KRaft/Keycloak finished starting.**
  `postgres.yaml`/`kafka.yaml`/`keycloak.yaml`'s `readinessProbe`s already had generous
  `failureThreshold`s, but their `livenessProbe`s were left at Kubernetes' default
  (`failureThreshold: 3`) — Kafka's combined broker+controller KRaft formatting and
  Keycloak's realm import both regularly exceed that window, so the kubelet killed the
  container mid-startup, every time, before the readiness probe ever got a chance. Fixed by
  giving every dependency's liveness probe the same generous threshold as its readiness
  probe.
- **Kafka's controller quorum voter address created a startup deadlock.** With
  `KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093` (the Service, not the pod), the broker's own
  self-registration with the controller quorum — itself, in this single-node setup — routed
  through the `kafka` Service. A Kubernetes Service only forwards to `Ready` endpoints, and
  this pod cannot become `Ready` until it finishes registering with the controller. Real
  symptom: `Received a fatal error while waiting for the controller to acknowledge that we
  are caught up`, repeating forever. Fixed by pointing the controller quorum voter at
  `1@localhost:9093` — correct for a single-voter KRaft cluster, since the voter is always
  the same process, and it sidesteps the Service's readiness gate entirely.
- **A `readinessProbe` on the mock-payment-provider sidecar could never pass.** Kubernetes
  probes — including `tcpSocket` — always connect via the Pod IP from the kubelet, never via
  loopback, even for a probe scoped to one container in a multi-container Pod. But
  `MockPaymentProviderServer` intentionally binds only `127.0.0.1` (a Milestone 2 finding,
  unchanged here). The probe failed every time by construction. Removed it: the main
  container's own probes already gate Pod readiness on the ~60s Spring Boot startup, by which
  point the sidecar (a plain `HttpServer`, listening within ~1s) is certainly already up.
- **Keycloak's dynamic hostname produced a token issuer mismatch.** With
  `KC_HOSTNAME_STRICT=false`, Keycloak derives each token's `iss` claim from the request's
  `Host` header. The smoke test mints tokens through `kubectl port-forward`
  (`127.0.0.1:18081`); the application validates `iss` against `LEDGERFLOW_OAUTH2_ISSUER`
  (`http://keycloak:8080/realms/ledgerflow`, the in-cluster Service DNS name). Those two
  request paths produced two different `iss` values for the same realm, and the resource
  server correctly rejected the mismatch with a real `401`. Fixed by pinning
  `KC_HOSTNAME=http://keycloak:8080` so every minted token has the same issuer regardless of
  how the token endpoint was reached — the same fix a real (non-`kind`) deployment would need
  for the same reason.
- **OTLP log/trace export logs `ERROR`-level connection failures every few seconds.**
  Expected, not a regression: no observability stack is deployed in this milestone (Current
  state, Milestone 4), so `LEDGERFLOW_OTLP_TRACES_ENDPOINT`/`LOGS_ENDPOINT` are left unset,
  and the application falls back to its built-in `localhost:4318` default, which nothing is
  listening on. Per `README.md`, "telemetry failure never changes or rolls back the order" —
  confirmed true here too (the smoke test's order still completes normally) — but it does
  make `kubectl logs` noisy. Left as-is rather than engineered around, since fixing it
  properly means deploying the observability stack in-cluster, which is out of scope for this
  milestone.

## Reproducing this milestone's evidence

```bash
scripts/kind-up            # one-command deploy
scripts/kind-smoke-test    # real order creation + worker independence + NetworkPolicy proof
kubectl --namespace ledgerflow get deployments,pods,svc,hpa,pdb,networkpolicy
scripts/kind-down          # deletes the cluster
```
