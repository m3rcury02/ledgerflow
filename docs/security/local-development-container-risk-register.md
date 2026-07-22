# Local-development container vulnerability risk register

## Scope and decision authority

This register records the maintainer's narrowly identified container-image risk decisions,
initially accepted on 2026-07-14 and re-reviewed on 2026-07-22. Acceptance applies only to the
loopback-bound, single-host Docker Compose environment used for LedgerFlow development and
demonstrations. It is not a production risk decision, must not be copied into a production
scanner policy, and does not make any listed image suitable for production deployment.

The accepted findings are machine-matched by exact image tag, image digest, scanner target, package, installed version, and vulnerability identifier in [`config/security/local-compose-vulnerability-exceptions.json`](../../config/security/local-compose-vulnerability-exceptions.json). Trivy still prints every finding. Repository-secret and packaged-application scans have no exception path and continue to fail normally. A new finding, changed digest, stale finding, missing risk record, or expired record fails the command.

Each active record has its own acceptance and expiry timestamps and lasts no more than 30 days,
or until a compatible fixed official image is available, whichever is earlier. Grouped
identifiers share the exact same image, target, package, installed version, reachability
assessment, decision, owner, and review dates; every vulnerability and scanner-reported fixed
version is still enumerated. Remediated records remain here as immutable decision history but
are removed from the machine exception policy.

## Evidence and completed remediation

- Evidence date: `2026-07-14` UTC.
- Scanner: Trivy `0.72.0`, pinned by version and digest in `scripts/security-scan`, with refreshed vulnerability and Java databases, `--ignore-unfixed`, and severities `HIGH,CRITICAL`.
- Image identity: the multi-platform repository digests recorded below and in the policy were resolved after an explicit pull from the official registries.
- `prom/prometheus:v3.13.0` was replaced by the compatible official patch `v3.13.1`; the replacement digest `prom/prometheus@sha256:3c42b892cf723fa54d2f262c37a0e1f80aa8c8ddb1da7b9b0df9455a35a7f893` scans clean.
- `postgres:18.4-alpine3.24` was replaced by the same PostgreSQL release on the official `postgres:18.4-trixie` image. This removes the fixed Alpine `c-ares` finding without changing the PostgreSQL service version or contract; the separate `gosu` findings remain below.
- The current official Kafka native image scans clean. Current same-series Grafana, Loki, Tempo,
  Keycloak, and Valkey tags provide no compatible patched official image for the remaining
  tuples. The same-version Valkey Debian variant retains the OpenSSL finding and adds another
  fixed OS finding, so it is not a safer replacement.
- Re-review evidence date: `2026-07-22` UTC, using the same scanner with freshly downloaded
  vulnerability databases. The packaged application PostgreSQL driver finding
  `CVE-2026-54291` was remediated by selecting `42.7.12`; application and repository-secret
  gates remain unsuppressible.
- `otel/opentelemetry-collector-contrib:0.156.0` was replaced by the compatible official
  `0.157.0` image. The replacement scans clean, so LF-DEV-2026-012 was removed from the active
  machine policy.
- The refreshed database reported `GHSA-hrxh-6v49-42gf`, which has no CVE alias, against gRPC
  versions below `1.82.1`. The exact-policy schema now accepts either CVE or GHSA identifiers;
  it still requires exact tuples and does not hide scanner output. Grafana `13.1.1` was also
  checked and retains affected gRPC dependencies, so it is not a compatible remediation.
- Current official Loki `3.7.3`, Prometheus `3.13.1`, and Keycloak `26.7.0` images retain their
  new findings. Tempo `3.0.2` is a major-version service-contract change rather than a
  compatible 2.x patch; the current compatible Tempo `2.10.7` remains affected.

## Accepted records

### LF-DEV-2026-001 — PostgreSQL entrypoint `gosu` Go standard library

- Vulnerability identifier and severity: `CVE-2025-68121` (CRITICAL); `CVE-2025-61726`, `CVE-2025-61729`, `CVE-2026-25679`, `CVE-2026-27145`, `CVE-2026-32280`, `CVE-2026-32281`, `CVE-2026-32283`, `CVE-2026-33811`, `CVE-2026-33814`, `CVE-2026-39820`, `CVE-2026-39822`, `CVE-2026-39836`, `CVE-2026-42499`, and `CVE-2026-42504` (HIGH).
- Affected image and installed package: `postgres:18.4-trixie` at `postgres@sha256:3a82e1f56c8f0f5616a11103ac3d47e632c3938698946a7ad26da0df1334744a`; `usr/local/bin/gosu`, package `stdlib` `v1.24.6`.
  `scripts/security-scan.yml`'s first real GitHub Actions run on `main` (2026-07-19/20) found
  this tag's digest had already drifted twice from the originally recorded one in under two
  days — a fresh CI pull always sees the registry's current digest, while a locally-cached
  image does not, so this was invisible to every local run until real CI existed. Re-pinning
  the exception's digest alone was not durable (the tag drifted again before the next CI run
  finished). `compose.yaml` now pins this image by digest directly
  (`postgres:18.4-trixie@sha256:...`) instead of the mutable tag, so the image Compose actually
  runs is content-addressed and immutable regardless of upstream rebuild cadence; this exception
  entry's `image`/`digest` fields match that pin exactly. The package, installed version, and
  full CVE set below are unchanged across every observed digest — this remains the same
  accepted risk from the original `2026-07-14` review, re-pinned to a stable reference, not a
  new review. Upgrading past this pin requires a deliberate maintainer decision, same as any
  other pinned dependency.
- Fixed versions: `CVE-2025-68121` — `1.24.13, 1.25.7, 1.26.0-rc.3`; `CVE-2025-61726` — `1.24.12, 1.25.6`; `CVE-2025-61729` — `1.24.11, 1.25.5`; `CVE-2026-25679` — `1.25.8, 1.26.1`; `CVE-2026-27145` and `CVE-2026-42504` — `1.25.11, 1.26.4`; `CVE-2026-32280`, `CVE-2026-32281`, and `CVE-2026-32283` — `1.25.9, 1.26.2`; `CVE-2026-33811`, `CVE-2026-33814`, `CVE-2026-39820`, `CVE-2026-39836`, and `CVE-2026-42499` — `1.25.10, 1.26.3`; `CVE-2026-39822` — `1.25.12, 1.26.5, 1.27.0-rc.2`.
- Compatible patched official image currently available: No. Both current official PostgreSQL 18.4 variants tested contain the same `gosu` binary; the Debian variant removes the separate Alpine OS finding.
- Reachability or use: `gosu` executes at container startup to drop privileges. The LedgerFlow flow does not supply untrusted `gosu` arguments or call Go network/protocol APIs through it, but binary-level exploitability has not been disproved.
- Mitigation already present: loopback-only database port, local placeholder credentials, trusted official image, non-root database process after entrypoint, resource limits, and development-only data.
- Residual risk: a malicious local user, compromised image input, or an affected startup path could compromise the development database container or local data.
- Decision: temporarily accept for local development; replace the image immediately when the official PostgreSQL tag rebuilds with fixed `gosu`.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Expiry date: `2026-08-13T10:55:00Z` or compatible fixed official image availability, whichever is earlier.
- Re-review trigger: image/tag/digest or scanner-result change, a fixed official PostgreSQL 18 image, evidence that an affected path is reachable, any non-local deployment, or expiry.

### LF-DEV-2026-002 — Grafana server Tempo dependency

- Vulnerability identifier and severity: `CVE-2026-21728` and `CVE-2026-28377` (HIGH).
- Affected image and installed package: `grafana/grafana:13.1.1-ubuntu` at
  `grafana/grafana@sha256:5a9df011defa8384ee01fc9b393854daecc6afb98132c66e2e658b3f564830e8`;
  `usr/share/grafana/bin/grafana`, package `github.com/grafana/tempo`
  `v1.5.1-0.20260427112133-525d1bab07e0`.
- Fixed versions: `CVE-2026-21728` — `2.8.4, 2.9.2, 2.10.2`; `CVE-2026-28377` — `2.10.3`.
- Compatible patched official image currently available: No; the current `13.1.1` patch retains
  the affected dependency.
- Reachability or use: the Grafana server is used locally and the package is linked into the active binary. The specific vulnerable Tempo code path is not required by the provisioned dashboards, but reachability is not disproved.
- Mitigation already present: loopback-only port, no sign-up, local admin credential from environment, analytics and plugin downloads disabled, only version-controlled data sources, and resource limits.
- Residual risk: a crafted request or data-source response on the local machine could reach affected server code and compromise Grafana availability or local observability data.
- Decision: temporarily accept for local development; upgrade to the first compatible fixed official 13.1 patch.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Expiry date: `2026-08-13T10:55:00Z` or compatible fixed official image availability, whichever is earlier.
- Re-review trigger: new Grafana 13.1 patch/rebuild, digest or finding change, proof of reachability, non-local exposure, or expiry.

### LF-DEV-2026-003 — Grafana server Go standard library

- Vulnerability identifier and severity: `CVE-2026-39822` (HIGH).
- Affected image and installed package: `grafana/grafana:13.1.0-ubuntu` at the LF-DEV-2026-002 digest; `usr/share/grafana/bin/grafana`, package `stdlib` `v1.26.4`.
- Fixed version: `1.25.12, 1.26.5, 1.27.0-rc.2`.
- Compatible patched official image currently available: Yes; `13.1.1-ubuntu` no longer reports
  this tuple.
- Reachability or use: the affected standard library is linked into the active local Grafana server; exact vulnerable-path reachability is not established.
- Mitigation already present: the LF-DEV-2026-002 loopback, authentication, plugin, data-source, and resource controls.
- Residual risk: none under this record after the image replacement.
- Decision: remediated on `2026-07-22` by upgrading Compose to `13.1.1-ubuntu`; removed from the
  active exception policy.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Acceptance expiry: closed early on `2026-07-22` (the original outer bound was
  `2026-08-13T10:55:00Z`).
- Re-review trigger: new Grafana image, digest/finding change, reachability evidence, non-local exposure, or expiry.

### LF-DEV-2026-004 — Bundled Grafana Elasticsearch data-source plugin

- Vulnerability identifier and severity: `CVE-2026-27145`, `CVE-2026-39822`, and `CVE-2026-42504` (HIGH).
- Affected image and installed package: `grafana/grafana:13.1.1-ubuntu` at the
  LF-DEV-2026-002 digest; bundled Elasticsearch plugin binary, package `stdlib` `v1.26.3`.
- Fixed versions: `CVE-2026-27145` and `CVE-2026-42504` — `1.25.11, 1.26.4`; `CVE-2026-39822` — `1.25.12, 1.26.5, 1.27.0-rc.2`.
- Compatible patched official image currently available: No.
- Reachability or use: the plugin exists in the image but LedgerFlow provisions no Elasticsearch data source and does not use the plugin.
- Mitigation already present: plugin downloads are disabled, provisioned data sources are read-only files, Grafana is loopback-only, and untrusted users are not created.
- Residual risk: a local administrator could enable the bundled plugin, or a Grafana compromise could invoke it.
- Decision: temporarily accept for local development pending an official fixed image.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Expiry date: `2026-08-13T10:55:00Z` or fixed image availability, whichever is earlier.
- Re-review trigger: plugin configuration/use, Grafana update, digest/finding change, non-local exposure, or expiry.

### LF-DEV-2026-005 — Bundled Grafana Zipkin plugin Go standard library

- Vulnerability identifier and severity: `CVE-2026-27145`, `CVE-2026-39822`, and
  `CVE-2026-42504` (HIGH). The `13.1.1-ubuntu` update removed the other nine tuples previously
  recorded under this identifier.
- Affected image and installed package: `grafana/grafana:13.1.1-ubuntu` at the
  LF-DEV-2026-002 digest; bundled Zipkin plugin binary, package `stdlib` `v1.26.3`.
- Fixed versions: `CVE-2026-27145` and `CVE-2026-42504` — `1.25.11, 1.26.4`;
  `CVE-2026-39822` — `1.25.12, 1.26.5, 1.27.0-rc.2`.
- Compatible patched official image currently available: No.
- Reachability or use: the plugin exists but LedgerFlow provisions Tempo, not Zipkin, and does not configure this plugin.
- Mitigation already present: the LF-DEV-2026-004 plugin, loopback, authentication, and provisioning controls.
- Residual risk: local administrative enablement or a preceding Grafana compromise could expose the affected plugin code.
- Decision: temporarily accept for local development pending an official fixed image.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Expiry date: `2026-08-13T10:55:00Z` or fixed image availability, whichever is earlier.
- Re-review trigger: Zipkin plugin use, Grafana update, digest/finding change, non-local exposure, or expiry.

### LF-DEV-2026-006 — Bundled Grafana Zipkin plugin `x/net`

- Vulnerability identifier and severity: `CVE-2026-25681`, `CVE-2026-27136`, `CVE-2026-33814`, and `CVE-2026-39821` (HIGH).
- Affected image and installed package: `grafana/grafana:13.1.1-ubuntu` at the
  LF-DEV-2026-002 digest; bundled Zipkin plugin binary, package `golang.org/x/net` `v0.49.0`.
- Fixed versions: `CVE-2026-33814` — `0.53.0`; the other listed identifiers — `0.55.0`.
- Compatible patched official image currently available: No; `13.1.1-ubuntu` retains the
  affected dependency.
- Reachability or use: the bundled Zipkin plugin is not configured or used.
- Mitigation already present: the LF-DEV-2026-004 plugin, loopback, authentication, and provisioning controls.
- Residual risk: local administrative enablement or a preceding Grafana compromise could expose affected networking code.
- Decision: temporarily accept for local development pending an official fixed image.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Expiry date: `2026-08-13T10:55:00Z` or fixed image availability, whichever is earlier.
- Re-review trigger: Zipkin plugin use, Grafana update, digest/finding change, non-local exposure, or expiry.

### LF-DEV-2026-007 — Bundled Grafana Zipkin plugin OpenTelemetry API

- Vulnerability identifier and severity: `CVE-2026-29181` (HIGH).
- Affected image and installed package: `grafana/grafana:13.1.0-ubuntu` at the LF-DEV-2026-002 digest; bundled Zipkin plugin binary, package `go.opentelemetry.io/otel` `v1.40.0`.
- Fixed version: `1.41.0`.
- Compatible patched official image currently available: Yes; `13.1.1-ubuntu` no longer reports
  this tuple.
- Reachability or use: the bundled Zipkin plugin is not configured or used.
- Mitigation already present: the LF-DEV-2026-004 plugin, loopback, authentication, and provisioning controls.
- Residual risk: none under this record after the image replacement.
- Decision: remediated on `2026-07-22` by upgrading Compose to `13.1.1-ubuntu`; removed from the
  active exception policy.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Acceptance expiry: closed early on `2026-07-22` (the original outer bound was
  `2026-08-13T10:55:00Z`).
- Re-review trigger: Zipkin plugin use, Grafana update, digest/finding change, non-local exposure, or expiry.

### LF-DEV-2026-008 — Bundled Grafana Zipkin plugin OpenTelemetry SDK

- Vulnerability identifier and severity: `CVE-2026-39883` (HIGH).
- Affected image and installed package: `grafana/grafana:13.1.0-ubuntu` at the LF-DEV-2026-002 digest; bundled Zipkin plugin binary, package `go.opentelemetry.io/otel/sdk` `v1.40.0`.
- Fixed version: `1.43.0`.
- Compatible patched official image currently available: Yes; `13.1.1-ubuntu` no longer reports
  this tuple.
- Reachability or use: the bundled Zipkin plugin is not configured or used.
- Mitigation already present: the LF-DEV-2026-004 plugin, loopback, authentication, and provisioning controls.
- Residual risk: none under this record after the image replacement.
- Decision: remediated on `2026-07-22` by upgrading Compose to `13.1.1-ubuntu`; removed from the
  active exception policy.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Acceptance expiry: closed early on `2026-07-22` (the original outer bound was
  `2026-08-13T10:55:00Z`).
- Re-review trigger: Zipkin plugin use, Grafana update, digest/finding change, non-local exposure, or expiry.

### LF-DEV-2026-009 — Loki Apache Thrift dependency

- Vulnerability identifier and severity: `CVE-2026-41602` (HIGH).
- Affected image and installed package: `grafana/loki:3.7.3` at `grafana/loki@sha256:70b9f699fc9bb868b62f1cfd4f787dfa50242f1fd92e6089787d5d7daea75fe8`; `usr/bin/loki`, package `github.com/apache/thrift` `v0.22.0`.
- Fixed version: `0.23.0`.
- Compatible patched official image currently available: No; `3.7.3` is the current official 3.7 release.
- Reachability or use: Loki is active for local logs. LedgerFlow sends OTLP through the Collector and does not intentionally use a Thrift ingestion path, but linked-code reachability is not disproved.
- Mitigation already present: loopback-only endpoint, local Docker network, no public tenant, bounded retention/resource limits, and development-only logs.
- Residual risk: crafted local telemetry or an affected internal code path could disrupt or compromise the local log service.
- Decision: temporarily accept for local development pending a compatible official Loki patch.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Expiry date: `2026-08-13T10:55:00Z` or fixed image availability, whichever is earlier.
- Re-review trigger: Loki patch/rebuild, protocol configuration change, digest/finding change, non-local exposure, or expiry.

### LF-DEV-2026-010 — Loki Go standard library

- Vulnerability identifier and severity: `CVE-2026-39822` (HIGH).
- Affected image and installed package: `grafana/loki:3.7.3` at the LF-DEV-2026-009 digest; `usr/bin/loki`, package `stdlib` `v1.26.4`.
- Fixed version: `1.25.12, 1.26.5, 1.27.0-rc.2`.
- Compatible patched official image currently available: No.
- Reachability or use: the standard library is linked into the active local Loki service; exact vulnerable-path reachability is not established.
- Mitigation already present: the LF-DEV-2026-009 loopback, network, retention, and resource controls.
- Residual risk: malicious local telemetry could exploit affected standard-library behavior.
- Decision: temporarily accept for local development pending a compatible official Loki patch.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Expiry date: `2026-08-13T10:55:00Z` or fixed image availability, whichever is earlier.
- Re-review trigger: Loki patch/rebuild, digest/finding change, reachability evidence, non-local exposure, or expiry.

### LF-DEV-2026-011 — Tempo Go standard library

- Vulnerability identifier and severity: `CVE-2026-27145`, `CVE-2026-39822`, and `CVE-2026-42504` (HIGH).
- Affected image and installed package: `grafana/tempo:2.10.7` at `grafana/tempo@sha256:032b3acb51ed02c4b801473d54bb63e9e9f13738d215126d9843c30283794f4b`; `tempo`, package `stdlib` `v1.26.3`.
- Fixed versions: `CVE-2026-27145` and `CVE-2026-42504` — `1.25.11, 1.26.4`; `CVE-2026-39822` — `1.25.12, 1.26.5, 1.27.0-rc.2`.
- Compatible patched official image currently available: No; `2.10.7` is the current official 2.10 release.
- Reachability or use: Tempo actively receives and serves local traces, so affected standard-library code may be reachable even though no exploit path is confirmed.
- Mitigation already present: loopback-only endpoint, local Docker network, local filesystem backend, bounded resources, and development-only traces without sensitive payloads.
- Residual risk: crafted local telemetry could disrupt or compromise the trace service or local trace data.
- Decision: temporarily accept for local development pending a compatible official Tempo patch.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Expiry date: `2026-08-13T10:55:00Z` or fixed image availability, whichever is earlier.
- Re-review trigger: Tempo patch/rebuild, digest/finding change, reachability evidence, non-local exposure, or expiry.

### LF-DEV-2026-012 — OpenTelemetry Collector Go standard library

- Vulnerability identifier and severity: `CVE-2026-39822` (HIGH).
- Affected image and installed package: `otel/opentelemetry-collector-contrib:0.156.0` at `otel/opentelemetry-collector-contrib@sha256:125bdbeb7590cc1952c5b3430ecf14063568980c2c93d5b38676cc0446ed8108`; `otelcol-contrib`, package `stdlib` `v1.26.4`.
- Fixed version: `1.25.12, 1.26.5, 1.27.0-rc.2`.
- Compatible patched official image currently available: Yes. `0.157.0` was published and its
  official image scans clean for HIGH/CRITICAL fixed vulnerabilities.
- Reachability or use: the Collector actively accepts HTTP/gRPC telemetry and exports to local Tempo/Loki, so affected code may be reachable even though no exploit path is confirmed.
- Mitigation already present: loopback-only host receivers, local Docker network, fixed version-controlled pipeline, bounded resources, and no sensitive telemetry payloads.
- Residual risk: none under this record after the image replacement; normal future-image scan
  risk remains governed by `scripts/security-scan`.
- Decision: remediated on `2026-07-22` by upgrading Compose to `0.157.0`; removed from the active
  exception policy.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Acceptance expiry: closed early on `2026-07-22` when the compatible fixed image became
  available (the original outer bound was `2026-08-13T10:55:00Z`).
- Re-review trigger: any Collector image, digest, or scanner-result change, or non-local receiver
  exposure.

### LF-DEV-2026-013 — Keycloak Jackson Databind

- Vulnerability identifier and severity: `CVE-2026-54512` and `CVE-2026-54513` (HIGH). Trivy reports each tuple twice in the Java target; the exception matches the tuple and does not remove duplicate scanner output.
- Affected image and installed package: `quay.io/keycloak/keycloak:26.7.0` at `quay.io/keycloak/keycloak@sha256:2eb3cd316835c990e69e26ade292ffa78f6fb0db7d5fc6377463c162e1979ac0`; Java package `com.fasterxml.jackson.core:jackson-databind` `2.21.2`.
- Fixed versions: `CVE-2026-54512` and `CVE-2026-54513` — `2.18.8, 2.21.4, 3.1.4`.
- Compatible patched official image currently available: No; Keycloak `26.7.0` is the current official release.
- Reachability or use: Keycloak uses Jackson for server JSON handling and realm import, so the dependency is active; the exact vulnerable input path is not confirmed.
- Mitigation already present: loopback-only service, no committed users/passwords/client secrets, environment-supplied local credentials, imported fixed realm, local PostgreSQL, and resource limits.
- Residual risk: crafted requests from the local host could reach affected JSON behavior and compromise authentication-service availability or local realm data.
- Decision: temporarily accept for local development pending a fixed official Keycloak patch.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Expiry date: `2026-08-13T10:55:00Z` or fixed image availability, whichever is earlier.
- Re-review trigger: Keycloak patch/rebuild, digest/finding change, exploitability evidence, non-local exposure, or expiry.

### LF-DEV-2026-014 — Unused Keycloak SQL Server JDBC driver

- Vulnerability identifier and severity: `CVE-2025-59250` (HIGH).
- Affected image and installed package: `quay.io/keycloak/keycloak:26.7.0` at the LF-DEV-2026-013 digest; Java package `com.microsoft.sqlserver:mssql-jdbc` `13.2.1`.
- Fixed version: `10.2.4.jre11, 11.2.4.jre11, 12.2.1.jre11, 12.6.5.jre11, 12.8.2.jre11, 12.10.2.jre11, 13.2.1.jre11`.
- Compatible patched official image currently available: No; the current Keycloak image bundles the scanner-identified artifact and there is no later 26.7 image.
- Reachability or use: not used in LedgerFlow. Keycloak is explicitly configured with `KC_DB=postgres` and has no SQL Server URL or credentials.
- Mitigation already present: PostgreSQL-only configuration, no SQL Server network dependency, loopback-only Keycloak, and local-only scope.
- Residual risk: classpath presence remains; a configuration change or a separate Keycloak flaw could make the driver reachable.
- Decision: temporarily accept for local development pending a fixed official Keycloak patch; fail review if the database configuration changes.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Expiry date: `2026-08-13T10:55:00Z` or fixed image availability, whichever is earlier.
- Re-review trigger: Keycloak database/configuration change, patch/rebuild, digest/finding change, non-local deployment, or expiry.

### LF-DEV-2026-015 — Valkey `libcrypto3`

- Vulnerability identifier and severity: `CVE-2026-45447` (HIGH).
- Affected image and installed package: `valkey/valkey:9.1.0-alpine3.23` at `valkey/valkey@sha256:a35428eba9043cc0b79dbe54100f0c92784f2de00ad09b01182bfb1c5c83d1bd`; Alpine package `libcrypto3` `3.5.6-r0`.
- Fixed version: `3.5.7-r0`.
- Compatible patched official image currently available: No. The current 9.1 Alpine tag has not been rebuilt; the same-version Debian variant retains the OpenSSL finding and adds a fixed `libcap2` finding.
- Reachability or use: Valkey runs locally but the LedgerFlow application does not connect to it in the current milestone; no TLS or application cache path uses this library.
- Mitigation already present: loopback-only port, ephemeral/no-persistence configuration, memory cap, no application credentials or sensitive data, and resource limits.
- Residual risk: a malicious local client or future accidental integration could reach affected crypto behavior or compromise the cache container.
- Decision: temporarily accept for local development pending an official rebuilt 9.1 image.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Expiry date: `2026-08-13T10:55:00Z` or fixed image availability, whichever is earlier.
- Re-review trigger: Valkey image rebuild/patch, application integration, TLS enablement, digest/finding change, non-local exposure, or expiry.

### LF-DEV-2026-016 — Valkey `libssl3`

- Vulnerability identifier and severity: `CVE-2026-45447` (HIGH).
- Affected image and installed package: `valkey/valkey:9.1.0-alpine3.23` at the LF-DEV-2026-015 digest; Alpine package `libssl3` `3.5.6-r0`.
- Fixed version: `3.5.7-r0`.
- Compatible patched official image currently available: No, for the reasons in LF-DEV-2026-015.
- Reachability or use: Valkey is not connected to the application and TLS is not configured in the local scenario.
- Mitigation already present: the LF-DEV-2026-015 loopback, ephemeral-data, memory, and non-integration controls.
- Residual risk: a malicious local client, future integration, or other container compromise could expose affected TLS behavior.
- Decision: temporarily accept for local development pending an official rebuilt 9.1 image.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Expiry date: `2026-08-13T10:55:00Z` or fixed image availability, whichever is earlier.
- Re-review trigger: Valkey image rebuild/patch, application integration, TLS enablement, digest/finding change, non-local exposure, or expiry.

### LF-DEV-2026-017 — Valkey `openssl`

- Vulnerability identifier and severity: `CVE-2026-45447` (HIGH).
- Affected image and installed package: `valkey/valkey:9.1.0-alpine3.23` at the LF-DEV-2026-015 digest; Alpine package `openssl` `3.5.6-r0`.
- Fixed version: `3.5.7-r0`.
- Compatible patched official image currently available: No, for the reasons in LF-DEV-2026-015.
- Reachability or use: Valkey is not connected to the application and no local workflow invokes the OpenSSL CLI.
- Mitigation already present: the LF-DEV-2026-015 loopback, ephemeral-data, memory, and non-integration controls.
- Residual risk: a malicious local client or container compromise could invoke affected OpenSSL behavior.
- Decision: temporarily accept for local development pending an official rebuilt 9.1 image.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Expiry date: `2026-08-13T10:55:00Z` or fixed image availability, whichever is earlier.
- Re-review trigger: Valkey image rebuild/patch, application integration, digest/finding change, non-local exposure, or expiry.

### LF-DEV-2026-018 — Grafana server gRPC

- Vulnerability identifier and severity: `GHSA-hrxh-6v49-42gf` (HIGH).
- Affected image and installed package: `grafana/grafana:13.1.1-ubuntu` at the
  LF-DEV-2026-002 digest; `usr/share/grafana/bin/grafana`, package
  `google.golang.org/grpc` `v1.81.1`.
- Fixed version: `1.82.1`.
- Compatible patched official image currently available: No. This official image was scanned on
  2026-07-22 and still contains `v1.81.1` in the server binary.
- Reachability or use: gRPC is linked into the active local Grafana server. LedgerFlow does not
  intentionally expose a Grafana gRPC listener, but affected-path reachability is not disproved.
- Mitigation already present: loopback-only port, authenticated local access, plugin downloads
  disabled, version-controlled data sources, and bounded container resources.
- Residual risk: crafted local input or a compromised data source could reach affected gRPC code
  and affect Grafana availability or local observability data.
- Decision: temporarily accept for local development; upgrade when a compatible official image
  includes gRPC `1.82.1` or later.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-22T00:00:00Z`.
- Expiry date: `2026-08-20T00:00:00Z` or compatible fixed official image availability,
  whichever is earlier.
- Re-review trigger: Grafana release/rebuild, digest or finding change, proof of reachability,
  non-local exposure, or expiry.

### LF-DEV-2026-019 — Bundled Grafana Elasticsearch plugin gRPC

- Vulnerability identifier and severity: `GHSA-hrxh-6v49-42gf` (HIGH).
- Affected image and installed package: `grafana/grafana:13.1.1-ubuntu` at the
  LF-DEV-2026-002 digest; bundled Elasticsearch plugin binary, package
  `google.golang.org/grpc` `v1.80.0`.
- Fixed version: `1.82.1`.
- Compatible patched official image currently available: No. This official image still contains
  affected gRPC in the bundled plugin.
- Reachability or use: the plugin exists in the image but LedgerFlow provisions no Elasticsearch
  data source and does not use it.
- Mitigation already present: plugin downloads are disabled, data sources are provisioned from
  read-only files, Grafana is loopback-only, and no untrusted local users are provisioned.
- Residual risk: local administrative enablement or a prior Grafana compromise could expose the
  affected plugin code.
- Decision: temporarily accept for local development pending a compatible fixed official image.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-22T00:00:00Z`.
- Expiry date: `2026-08-20T00:00:00Z` or compatible fixed official image availability,
  whichever is earlier.
- Re-review trigger: plugin use, Grafana release/rebuild, digest or finding change, non-local
  exposure, or expiry.

### LF-DEV-2026-020 — Bundled Grafana Zipkin plugin gRPC

- Vulnerability identifier and severity: `GHSA-hrxh-6v49-42gf` (HIGH).
- Affected image and installed package: `grafana/grafana:13.1.1-ubuntu` at the
  LF-DEV-2026-002 digest; bundled Zipkin plugin binary, package `google.golang.org/grpc`
  `v1.79.3`.
- Fixed version: `1.82.1`.
- Compatible patched official image currently available: No. This official image still contains
  affected gRPC in the bundled plugin.
- Reachability or use: the plugin exists but LedgerFlow provisions Tempo, not Zipkin, and does
  not configure this plugin.
- Mitigation already present: the LF-DEV-2026-019 plugin, loopback, authentication, and
  provisioning controls.
- Residual risk: local administrative enablement or a prior Grafana compromise could expose the
  affected plugin code.
- Decision: temporarily accept for local development pending a compatible fixed official image.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-22T00:00:00Z`.
- Expiry date: `2026-08-20T00:00:00Z` or compatible fixed official image availability,
  whichever is earlier.
- Re-review trigger: Zipkin plugin use, Grafana release/rebuild, digest or finding change,
  non-local exposure, or expiry.

### LF-DEV-2026-021 — Loki gRPC

- Vulnerability identifier and severity: `GHSA-hrxh-6v49-42gf` (HIGH).
- Affected image and installed package: `grafana/loki:3.7.3` at the LF-DEV-2026-009 digest;
  `usr/bin/loki`, package `google.golang.org/grpc` `v1.81.1`.
- Fixed version: `1.82.1`.
- Compatible patched official image currently available: No; `3.7.3` remains the current
  compatible official Loki release and contains the affected dependency.
- Reachability or use: Loki actively receives local logs through OTLP and serves Grafana queries;
  gRPC is linked into the active binary.
- Mitigation already present: loopback-only endpoint, isolated local Docker network, bounded
  retention and resources, and telemetry redaction tests.
- Residual risk: crafted local telemetry could reach affected gRPC behavior and affect the local
  log service or data.
- Decision: temporarily accept for local development pending a compatible fixed official image.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-22T00:00:00Z`.
- Expiry date: `2026-08-20T00:00:00Z` or compatible fixed official image availability,
  whichever is earlier.
- Re-review trigger: Loki patch/rebuild, protocol change, digest or finding change, non-local
  exposure, or expiry.

### LF-DEV-2026-022 — Tempo gRPC

- Vulnerability identifier and severity: `GHSA-hrxh-6v49-42gf` (HIGH).
- Affected image and installed package: `grafana/tempo:2.10.7` at the LF-DEV-2026-011 digest;
  `tempo`, package `google.golang.org/grpc` `v1.80.0`.
- Fixed version: `1.82.1`.
- Compatible patched official image currently available: No. `2.10.7` remains the compatible
  2.x release; moving to `3.0.2` would be an unreviewed major service-contract change.
- Reachability or use: Tempo actively receives and serves local trace data, so the linked gRPC
  dependency may be reachable.
- Mitigation already present: loopback-only endpoint, isolated local Docker network, filesystem
  backend, bounded resources, and telemetry redaction controls.
- Residual risk: crafted local telemetry could affect Tempo availability or local trace data.
- Decision: temporarily accept for local development; do not silently cross the Tempo major
  version boundary solely to clear the scanner.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-22T00:00:00Z`.
- Expiry date: `2026-08-20T00:00:00Z` or compatible fixed official image availability,
  whichever is earlier.
- Re-review trigger: compatible Tempo 2.x patch, approved 3.x migration, digest or finding
  change, non-local exposure, or expiry.

### LF-DEV-2026-023 — Prometheus server gRPC

- Vulnerability identifier and severity: `GHSA-hrxh-6v49-42gf` (HIGH).
- Affected image and installed package: `prom/prometheus:v3.13.1` at
  `prom/prometheus@sha256:3c42b892cf723fa54d2f262c37a0e1f80aa8c8ddb1da7b9b0df9455a35a7f893`;
  `bin/prometheus`, package `google.golang.org/grpc` `v1.81.1`.
- Fixed version: `1.82.1`.
- Compatible patched official image currently available: No; `v3.13.1` remains the current
  official compatible release and contains the affected dependency.
- Reachability or use: Prometheus is active locally; gRPC is linked into its server binary,
  though LedgerFlow does not intentionally expose a Prometheus gRPC endpoint.
- Mitigation already present: loopback-only port, isolated local network, protected application
  management interface, bounded retention/resources, and fixed scrape targets.
- Residual risk: malicious local input or a compromised scrape target could reach affected code
  and affect local monitoring availability or data.
- Decision: temporarily accept for local development pending a compatible fixed official image.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-22T00:00:00Z`.
- Expiry date: `2026-08-20T00:00:00Z` or compatible fixed official image availability,
  whichever is earlier.
- Re-review trigger: Prometheus release/rebuild, scrape topology change, digest or finding change,
  non-local exposure, or expiry.

### LF-DEV-2026-024 — Promtool gRPC

- Vulnerability identifier and severity: `GHSA-hrxh-6v49-42gf` (HIGH).
- Affected image and installed package: `prom/prometheus:v3.13.1` at the LF-DEV-2026-023
  digest; `bin/promtool`, package `google.golang.org/grpc` `v1.81.1`.
- Fixed version: `1.82.1`.
- Compatible patched official image currently available: No; the current image bundles the
  affected validation binary.
- Reachability or use: `promtool` is invoked only by version-controlled validation scripts with
  repository-owned rule and configuration files; it is not a running network service.
- Mitigation already present: trusted inputs, short-lived validation container, dropped
  capabilities, no credentials, and no public endpoint.
- Residual risk: a malicious repository change executed by a developer could reach affected code
  during local validation.
- Decision: temporarily accept for local development pending a compatible fixed official image.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-22T00:00:00Z`.
- Expiry date: `2026-08-20T00:00:00Z` or compatible fixed official image availability,
  whichever is earlier.
- Re-review trigger: Prometheus release/rebuild, validation-input trust change, digest or finding
  change, or expiry.

### LF-DEV-2026-025 — Keycloak Jackson Core

- Vulnerability identifier and severity: `GHSA-r7wm-3cxj-wff9` (HIGH).
- Affected image and installed package: `quay.io/keycloak/keycloak:26.7.0` at the
  LF-DEV-2026-013 digest; Java package `com.fasterxml.jackson.core:jackson-core` `2.21.2`.
- Fixed versions: `2.18.8`, `2.21.4`, and `2.22.1`.
- Compatible patched official image currently available: No; `26.7.0` remains the current
  official Keycloak release and contains `2.21.2`.
- Reachability or use: Keycloak actively parses JSON requests and the local realm import, so the
  dependency is used; the specific vulnerable input path is not confirmed.
- Mitigation already present: loopback-only port, no committed users or secrets,
  environment-supplied local credentials, a version-controlled realm, local PostgreSQL, and
  bounded resources.
- Residual risk: crafted requests from the local host could affect Keycloak availability or local
  realm data.
- Decision: temporarily accept for local development pending a fixed official Keycloak image.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-22T00:00:00Z`.
- Expiry date: `2026-08-20T00:00:00Z` or compatible fixed official image availability,
  whichever is earlier.
- Re-review trigger: Keycloak release/rebuild, digest or finding change, exploitability evidence,
  non-local exposure, or expiry.

### LF-DEV-2026-026 — Keycloak base-image `libacl`

- Vulnerability identifier and severity: `CVE-2026-54369` (HIGH).
- Affected image and installed package: `quay.io/keycloak/keycloak:26.7.0` at the
  LF-DEV-2026-013 digest; Red Hat 9.8 package `libacl` `2.3.1-4.el9`.
- Fixed version: `2.4.0-1.el9_8`.
- Compatible patched official image currently available: No; the current official Keycloak image
  has not been rebuilt on the fixed base package.
- Reachability or use: the library is installed in the running image. LedgerFlow does not expose
  filesystem ACL administration through Keycloak, but package-level reachability is not
  disproved.
- Mitigation already present: non-root runtime, loopback-only port, no host filesystem mounts,
  no extra capabilities, and development-only data.
- Residual risk: a preceding local-container compromise or affected filesystem operation could
  reach the vulnerable library.
- Decision: temporarily accept for local development pending a fixed official Keycloak image.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-22T00:00:00Z`.
- Expiry date: `2026-08-20T00:00:00Z` or compatible fixed official image availability,
  whichever is earlier.
- Re-review trigger: Keycloak release/rebuild, base-package change, digest or finding change,
  non-local deployment, or expiry.

### LF-DEV-2026-027 — Bundled Keycloak PostgreSQL driver

- Vulnerability identifier and severity: `CVE-2026-54291` (HIGH).
- Affected image and installed package: `quay.io/keycloak/keycloak:26.7.0` at the
  LF-DEV-2026-013 digest; Java package `org.postgresql:postgresql` `42.7.11`.
- Fixed version: `42.7.12`.
- Compatible patched official image currently available: No; the current official Keycloak image
  still bundles `42.7.11`. LedgerFlow's own application artifact was independently upgraded to
  `42.7.12` and scans clean.
- Reachability or use: Keycloak is configured with PostgreSQL, so its bundled driver is active in
  the local identity service.
- Mitigation already present: loopback-only Keycloak and database ports, dedicated local database,
  placeholder environment credentials, bounded resources, and no production identity data.
- Residual risk: affected PostgreSQL protocol processing could compromise local authentication
  availability or realm data.
- Decision: temporarily accept only for the local Keycloak container pending a fixed official
  image; no exception applies to the LedgerFlow application artifact.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-22T00:00:00Z`.
- Expiry date: `2026-08-20T00:00:00Z` or compatible fixed official image availability,
  whichever is earlier.
- Re-review trigger: Keycloak release/rebuild, database configuration change, digest or finding
  change, non-local deployment, or expiry.

## Operational use, validation, and rollback

Run `scripts/security-scan` on every security, dependency, or Compose-image change and at least daily while this exception is active. The command displays Trivy's detailed table before evaluating the exact policy. It fails if an accepted image digest changes, a tuple changes or disappears, a new HIGH/CRITICAL fixed finding appears, a record is absent, the 30-day bound is exceeded, or the exception expires. These failure modes are intentional re-review triggers, not reasons to broaden the policy.

No database migration is required. The PostgreSQL image change preserves PostgreSQL 18.4 and its existing named volume format. Rollback may restore the prior image tag only after an explicit local-data compatibility check, but the digest-bound policy will reject that rollback until fresh evidence and an approved risk decision are recorded. Never roll this exception policy into a production deployment or production scanner configuration.
