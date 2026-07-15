# Local-development container vulnerability risk register

## Scope and decision authority

This register records the maintainer's 2026-07-14 acceptance of narrowly identified container-image risk so Milestone 5C can close. The acceptance applies only to the loopback-bound, single-host Docker Compose environment used for LedgerFlow development and demonstrations. It is not a production risk decision, must not be copied into a production scanner policy, and does not make any listed image suitable for production deployment.

The accepted findings are machine-matched by exact image tag, image digest, scanner target, package, installed version, and vulnerability identifier in [`config/security/local-compose-vulnerability-exceptions.json`](../../config/security/local-compose-vulnerability-exceptions.json). Trivy still prints every finding. Repository-secret and packaged-application scans have no exception path and continue to fail normally. A new finding, changed digest, stale finding, missing risk record, or expired record fails the command.

Every record below temporarily accepts risk until `2026-08-13T10:55:00Z`, exactly 30 days after acceptance, or until a compatible fixed official image is available, whichever is earlier. Grouped identifiers share the exact same image, target, package, installed version, reachability assessment, decision, owner, and review dates; every vulnerability and scanner-reported fixed version is still enumerated.

## Evidence and completed remediation

- Evidence date: `2026-07-14` UTC.
- Scanner: Trivy `0.72.0`, pinned by version and digest in `scripts/security-scan`, with refreshed vulnerability and Java databases, `--ignore-unfixed`, and severities `HIGH,CRITICAL`.
- Image identity: the multi-platform repository digests recorded below and in the policy were resolved after an explicit pull from the official registries.
- `prom/prometheus:v3.13.0` was replaced by the compatible official patch `v3.13.1`; the replacement digest `prom/prometheus@sha256:3c42b892cf723fa54d2f262c37a0e1f80aa8c8ddb1da7b9b0df9455a35a7f893` scans clean.
- `postgres:18.4-alpine3.24` was replaced by the same PostgreSQL release on the official `postgres:18.4-trixie` image. This removes the fixed Alpine `c-ares` finding without changing the PostgreSQL service version or contract; the separate `gosu` findings remain below.
- The current official Kafka native image scans clean. Current same-series Grafana, Loki, Tempo, OpenTelemetry Collector, Keycloak, and Valkey tags provide no compatible patched official image for the remaining tuples. The same-version Valkey Debian variant retains the OpenSSL finding and adds another fixed OS finding, so it is not a safer replacement.

## Accepted records

### LF-DEV-2026-001 — PostgreSQL entrypoint `gosu` Go standard library

- Vulnerability identifier and severity: `CVE-2025-68121` (CRITICAL); `CVE-2025-61726`, `CVE-2025-61729`, `CVE-2026-25679`, `CVE-2026-27145`, `CVE-2026-32280`, `CVE-2026-32281`, `CVE-2026-32283`, `CVE-2026-33811`, `CVE-2026-33814`, `CVE-2026-39820`, `CVE-2026-39822`, `CVE-2026-39836`, `CVE-2026-42499`, and `CVE-2026-42504` (HIGH).
- Affected image and installed package: `postgres:18.4-trixie` at `postgres@sha256:b913fd5699b8bd23fa4b06d72ecdd939fad43b80fb8651bac06caa0e6d135cac`; `usr/local/bin/gosu`, package `stdlib` `v1.24.6`.
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
- Affected image and installed package: `grafana/grafana:13.1.0-ubuntu` at `grafana/grafana@sha256:41b0dc13bb4f6a63ae2facb6112bfa40db59c797e0b70f9d6795d838de04537b`; `usr/share/grafana/bin/grafana`, package `github.com/grafana/tempo` `v1.5.1-0.20260427112133-525d1bab07e0`.
- Fixed versions: `CVE-2026-21728` — `2.8.4, 2.9.2, 2.10.2`; `CVE-2026-28377` — `2.10.3`.
- Compatible patched official image currently available: No; `13.1.0` is the current official 13.1 release and no 13.1 patch tag exists.
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
- Compatible patched official image currently available: No; no compatible 13.1 patch/rebuild is published.
- Reachability or use: the affected standard library is linked into the active local Grafana server; exact vulnerable-path reachability is not established.
- Mitigation already present: the LF-DEV-2026-002 loopback, authentication, plugin, data-source, and resource controls.
- Residual risk: malicious local input could exploit affected standard-library behavior in Grafana.
- Decision: temporarily accept for local development pending an official fixed image.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Expiry date: `2026-08-13T10:55:00Z` or fixed image availability, whichever is earlier.
- Re-review trigger: new Grafana image, digest/finding change, reachability evidence, non-local exposure, or expiry.

### LF-DEV-2026-004 — Bundled Grafana Elasticsearch data-source plugin

- Vulnerability identifier and severity: `CVE-2026-27145`, `CVE-2026-39822`, and `CVE-2026-42504` (HIGH).
- Affected image and installed package: `grafana/grafana:13.1.0-ubuntu` at the LF-DEV-2026-002 digest; bundled Elasticsearch plugin binary, package `stdlib` `v1.26.3`.
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

- Vulnerability identifier and severity: `CVE-2026-25679`, `CVE-2026-27145`, `CVE-2026-32280`, `CVE-2026-32281`, `CVE-2026-32283`, `CVE-2026-33811`, `CVE-2026-33814`, `CVE-2026-39820`, `CVE-2026-39822`, `CVE-2026-39836`, `CVE-2026-42499`, and `CVE-2026-42504` (HIGH).
- Affected image and installed package: `grafana/grafana:13.1.0-ubuntu` at the LF-DEV-2026-002 digest; bundled Zipkin plugin binary, package `stdlib` `v1.25.7`.
- Fixed versions: `CVE-2026-25679` — `1.25.8, 1.26.1`; `CVE-2026-27145` and `CVE-2026-42504` — `1.25.11, 1.26.4`; `CVE-2026-32280`, `CVE-2026-32281`, and `CVE-2026-32283` — `1.25.9, 1.26.2`; `CVE-2026-33811`, `CVE-2026-33814`, `CVE-2026-39820`, `CVE-2026-39836`, and `CVE-2026-42499` — `1.25.10, 1.26.3`; `CVE-2026-39822` — `1.25.12, 1.26.5, 1.27.0-rc.2`.
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
- Affected image and installed package: `grafana/grafana:13.1.0-ubuntu` at the LF-DEV-2026-002 digest; bundled Zipkin plugin binary, package `golang.org/x/net` `v0.49.0`.
- Fixed versions: `CVE-2026-33814` — `0.53.0`; the other listed identifiers — `0.55.0`.
- Compatible patched official image currently available: No.
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
- Compatible patched official image currently available: No.
- Reachability or use: the bundled Zipkin plugin is not configured or used.
- Mitigation already present: the LF-DEV-2026-004 plugin, loopback, authentication, and provisioning controls.
- Residual risk: local administrative enablement or a preceding Grafana compromise could expose affected telemetry code.
- Decision: temporarily accept for local development pending an official fixed image.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Expiry date: `2026-08-13T10:55:00Z` or fixed image availability, whichever is earlier.
- Re-review trigger: Zipkin plugin use, Grafana update, digest/finding change, non-local exposure, or expiry.

### LF-DEV-2026-008 — Bundled Grafana Zipkin plugin OpenTelemetry SDK

- Vulnerability identifier and severity: `CVE-2026-39883` (HIGH).
- Affected image and installed package: `grafana/grafana:13.1.0-ubuntu` at the LF-DEV-2026-002 digest; bundled Zipkin plugin binary, package `go.opentelemetry.io/otel/sdk` `v1.40.0`.
- Fixed version: `1.43.0`.
- Compatible patched official image currently available: No.
- Reachability or use: the bundled Zipkin plugin is not configured or used.
- Mitigation already present: the LF-DEV-2026-004 plugin, loopback, authentication, and provisioning controls.
- Residual risk: local administrative enablement or a preceding Grafana compromise could expose affected telemetry code.
- Decision: temporarily accept for local development pending an official fixed image.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Expiry date: `2026-08-13T10:55:00Z` or fixed image availability, whichever is earlier.
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
- Compatible patched official image currently available: No. `0.156.0` is current on the evidence date; the upstream schedule lists `0.157.0` for `2026-07-20`, which requires immediate re-review when released.
- Reachability or use: the Collector actively accepts HTTP/gRPC telemetry and exports to local Tempo/Loki, so affected code may be reachable even though no exploit path is confirmed.
- Mitigation already present: loopback-only host receivers, local Docker network, fixed version-controlled pipeline, bounded resources, and no sensitive telemetry payloads.
- Residual risk: crafted local telemetry could disrupt or compromise the Collector or downstream local telemetry.
- Decision: temporarily accept for local development pending the next compatible official release.
- Owner: LedgerFlow maintainer.
- Acceptance date: `2026-07-14T10:55:00Z`.
- Expiry date: `2026-08-13T10:55:00Z` or fixed image availability, whichever is earlier.
- Re-review trigger: `0.157.0` publication, any rebuild, digest/finding change, non-local receiver exposure, or expiry.

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

## Operational use, validation, and rollback

Run `scripts/security-scan` on every security, dependency, or Compose-image change and at least daily while this exception is active. The command displays Trivy's detailed table before evaluating the exact policy. It fails if an accepted image digest changes, a tuple changes or disappears, a new HIGH/CRITICAL fixed finding appears, a record is absent, the 30-day bound is exceeded, or the exception expires. These failure modes are intentional re-review triggers, not reasons to broaden the policy.

No database migration is required. The PostgreSQL image change preserves PostgreSQL 18.4 and its existing named volume format. Rollback may restore the prior image tag only after an explicit local-data compatibility check, but the digest-bound policy will reject that rollback until fresh evidence and an approved risk decision are recorded. Never roll this exception policy into a production deployment or production scanner configuration.
