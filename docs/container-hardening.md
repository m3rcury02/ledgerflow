# Container Hardening

This document records Milestone 3 (production-oriented containers) of
`docs/plans/portfolio-extension-execplan.md`. Every claim below was checked with a real
command against a real build of this branch's `Dockerfile` on 2026-07-18 — nothing here is
asserted from the Dockerfile's contents alone.

## Multi-stage build, non-root execution

`Dockerfile` builds in a JDK Alpine stage and runs in a separate, smaller JRE Alpine stage
(both digest-pinned), copying only the built jar across. The runtime stage creates a
dedicated `ledgerflow` user/group and switches to it before `ENTRYPOINT`:

```bash
docker inspect ledgerflow:local --format '{{.Config.User}}'
# ledgerflow:ledgerflow
```

## API and worker are the same image, different configuration

There is one build artifact (`application/build/libs/ledgerflow.jar`) and one image. A
"worker" deployment is the identical image started with
`LEDGERFLOW_RECOVERY_WORKER_ENABLED=true` (the default) and, where a deployment wants a
dedicated background-processing replica set that doesn't receive public HTTP traffic, a
Kubernetes Service that simply doesn't target that replica set's pods (Milestone 4). There
is no separate "worker" Dockerfile or build target — see
`docs/plans/portfolio-extension-execplan.md`'s Milestone 2 Current State finding, which
confirmed the application has no separate worker process today; the outbox publisher and
operator retry worker are `@Scheduled` tasks in the same Spring context.

## Read-only root filesystem compatibility

Verified by actually running the container this way against the real dependency stack
(`scripts/dev-up` + a standalone mock payment provider), twice:

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

Both runs logged `Started LedgerFlowApplication` (10.9s and 13.9s respectively) with no
filesystem errors. The JVM's own temp usage (heap dump directory, JIT compilation
artifacts) and Tomcat's minimal scratch usage are satisfied entirely by the `/tmp` tmpfs
mount; nothing else in the application writes to the root filesystem — no local file
uploads, no on-disk cache, no writable working directory under `/app`. **Recommended
production flags**: `--read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m` (or the equivalent
Kubernetes `securityContext.readOnlyRootFilesystem: true` plus an `emptyDir` volume mounted
at `/tmp`, which Milestone 4 wires up).

## Graceful shutdown

`application/src/main/resources/application.yaml` already sets `server.shutdown: graceful`,
and `Dockerfile`'s `ENTRYPOINT` is exec-form JSON (`["java", "-jar", ...]`), so `SIGTERM`
reaches the JVM directly instead of a shell that would need to forward it. Verified with a
real signal against a container started through `performance/compose.perf.yaml`:

```bash
docker compose ... kill --signal SIGTERM app
```

Log timestamps from that run:

```
18:35:00.203  Commencing graceful shutdown. Waiting for active requests to complete
18:35:00.217  Graceful shutdown complete
...
18:35:00.358  HikariPool-1 - Shutdown completed.
```

**154.4ms** from the first graceful-shutdown log line to the last shutdown-related log line
— comfortably inside Docker's default 10s `stop` grace period and Kubernetes' default 30s
`terminationGracePeriodSeconds`. (A wall-clock measurement around the `docker compose kill`
invocation itself read ~10.5s in an earlier attempt; that number included `docker compose`
CLI startup overhead before the signal was even delivered, not JVM shutdown time — the log
timestamps above are the accurate figure.)

## Reproducible build

`application/build.gradle.kts`'s `bootJar` task now sets `isPreserveFileTimestamps = false`
and `isReproducibleFileOrder = true`. Verified by building the jar twice, independently
(`clean` between them, the second run with `--no-build-cache` so nothing was reused from
Gradle's cache):

```bash
./gradlew --no-daemon clean :application:bootJar --console=plain
sha256sum application/build/libs/ledgerflow.jar   # 627b3647dbe1076d33c5117901fadfa6ebb79b120f1ff14c347e61e9cf1d7e62

./gradlew --no-daemon clean :application:bootJar --no-build-cache --console=plain
sha256sum application/build/libs/ledgerflow.jar   # 627b3647dbe1076d33c5117901fadfa6ebb79b120f1ff14c347e61e9cf1d7e62
```

**Byte-identical.** The remaining source of build-to-build variation is the base image
digests, which are already pinned in `Dockerfile`, and the `IMAGE_REVISION`/`IMAGE_CREATED`
build args, which are deliberately *not* reproducible (they record the real commit and
build time in the image's OCI metadata — see below). Everything that determines runtime
behavior (the jar) is reproducible; everything that's supposed to vary between builds
(the metadata) does.

## OCI metadata

`Dockerfile`'s runtime stage sets standard `org.opencontainers.image.*` labels. Local
builds get honest `unknown` revision/created values (never a fabricated one);
`.github/workflows/ci.yml` passes the real commit SHA and build timestamp via
`--build-arg`. Verified:

```bash
docker inspect ledgerflow:local --format '{{json .Config.Labels}}'
```

```json
{
  "org.opencontainers.image.created": "2026-07-18T18:22:57Z",
  "org.opencontainers.image.description": "LedgerFlow deployable application (API; also the worker role via LEDGERFLOW_RECOVERY_WORKER_ENABLED)",
  "org.opencontainers.image.licenses": "NOASSERTION",
  "org.opencontainers.image.revision": "5e4362e64bf2b3c3e0b02d8e5334fe5d935313bc",
  "org.opencontainers.image.source": "https://github.com/m3rcury02/ledgerflow",
  "org.opencontainers.image.title": "ledgerflow",
  "org.opencontainers.image.version": "0.1.0-SNAPSHOT"
}
```

`licenses` is `NOASSERTION` (the correct SPDX convention for "no license declared") rather
than a guessed value, since the repository has no `LICENSE` file.

## SBOM and vulnerability scanning

`scripts/security-scan` (Milestone 1's Trivy setup) scans the Compose dependency images and
the packaged Java artifact, but not the application's own container image. `scripts/scan-image`
(new in this milestone; also `make image-scan`) closes that gap: it builds the image,
generates a CycloneDX SBOM with a pinned `syft` image, and Trivy-scans the image with the
exact same pinned Trivy image `scripts/security-scan` already uses.

```bash
./scripts/scan-image
```

```
Scanning ledgerflow:local for HIGH/CRITICAL vulnerabilities
...
Clean: no unaddressed HIGH/CRITICAL findings.
SBOM: build/image-scan/sbom.cyclonedx.json (CycloneDX 1.7, 880 components)
```

That clean result is not the first result. The first scan of the unmodified upstream
`eclipse-temurin:25-jre-alpine` base image found **5 real HIGH-severity findings**:
`libexpat` (CVE-2026-56131, CVE-2026-56407, CVE-2026-56408) and `p11-kit`/`p11-kit-trust`
(CVE-2026-2100) — all with fixes published upstream in Alpine, just not yet incorporated
into a new `eclipse-temurin` image build as of the pinned digest. Rather than exception-list
them (this repository's own `scripts/security-scan` explicitly has "no exception path" for
the packaged application — see `docs/development-workflow.md` — and that principle applies
here too), `apk info --rdepends` was used to check what actually needs these packages:

- `p11-kit`/`p11-kit-trust`: nothing in the image depends on them at all.
- `libexpat`: needed only by `fontconfig`, which is needed only by `font-dejavu`
  (font rendering for AWT/Java2D), which nothing else needs.

LedgerFlow is a headless JSON/HTTP API with no AWT, PDF, image, or PKCS#11
hardware-token usage, so `Dockerfile` now removes the whole closure
(`ttf-dejavu font-dejavu fontconfig encodings mkfontscale freetype libfontenc libexpat
p11-kit p11-kit-trust`, plus their now-orphaned dependencies `libbz2`, `libpng`,
`brotli-libs`, `libffi`, `libtasn1`) in the runtime stage. This is a real, verified
attack-surface reduction, not just a documented exception:

- Package count: 44 → 30.
- Installed size: 24.0 MiB → 11.1 MiB (package layer only).
- HIGH/CRITICAL findings: 5 → 0.
- A full end-to-end order (`POST /api/v1/orders` → `201 COMPLETED`/`CAPTURED`) against the
  real local stack still passes after the removal — nothing the application actually uses
  was removed.

## No embedded secrets

`Dockerfile` copies only source code and the Gradle wrapper into the builder stage and only
the built jar into the runtime stage — no `.env` file, no credentials, no key material is
ever part of the build context that reaches either stage (`.dockerignore` excludes `.env`
and `docs/`, `infra/`, `scripts/`, `config/security/` from the build context entirely). All
runtime configuration (database credentials, OAuth2 issuer, Kafka bootstrap servers, the
recovery-worker toggle) arrives exclusively through environment variables at container
start, exactly as documented in `README.md`'s "Run the application locally" section. The
`scripts/security-scan` repository-secret scan (`Trivy fs --scanners secret`) already
covers the source tree this image is built from, with no exception path.

## JVM resource settings

Java 25 (like every JDK since 10) auto-detects cgroup memory and CPU limits by default
(`-XX:+UseContainerSupport`), so no JVM flags are required for correct container-aware
sizing out of the box. For constrained or shared environments (relevant to Milestone 4's
Kubernetes `resources.limits`), the documented tuning points are:

- `-XX:MaxRAMPercentage=75.0` (default is also container-aware, but an explicit value
  avoids surprises if the container memory limit is tight relative to the working set).
- `-XX:+ExitOnOutOfMemoryError` — fail fast and let the orchestrator restart the container
  rather than continuing in a degraded state.
- `-XX:ActiveProcessorCount=N` only if a Kubernetes CPU *limit* (not just a request) is set
  significantly below the node's core count and the JVM's own cgroup detection needs an
  explicit override.

None of these are set in `Dockerfile` today because the default container-aware behavior is
already correct for this application's profile (no evidence of memory pressure in any
Milestone 2 experiment); they're documented here as the exact flags to add via
`JAVA_TOOL_OPTIONS` if Milestone 4's resource limits ever require it, rather than guessed at
in advance.

## Reproducing this milestone's evidence

```bash
docker build --tag ledgerflow:local .
./scripts/scan-image                      # SBOM + vulnerability scan (make image-scan)
./gradlew clean :application:bootJar && sha256sum application/build/libs/ledgerflow.jar
./gradlew clean :application:bootJar --no-build-cache && sha256sum application/build/libs/ledgerflow.jar
docker inspect ledgerflow:local --format '{{.Config.User}}'
docker inspect ledgerflow:local --format '{{json .Config.Labels}}'
```
