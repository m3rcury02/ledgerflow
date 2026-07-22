# syntax=docker/dockerfile:1
#
# Multi-stage build for the LedgerFlow deployable application. There is a single build
# artifact (application/build/libs/ledgerflow.jar); worker deployments and the one-shot Flyway
# task run this same image with different configuration (see docs/container-hardening.md), not
# separate images.
#
# Hardening properties, each verified with real commands recorded in
# docs/container-hardening.md: non-root user, read-only root filesystem compatibility
# (run with --read-only --tmpfs /tmp), graceful shutdown (exec-form ENTRYPOINT delivers
# SIGTERM to the JVM; server.shutdown=graceful in application.yaml), reproducible jar
# (Gradle bootJar strips timestamps and fixes file order), no embedded secrets (all
# configuration arrives via environment variables at runtime), and OCI metadata below.

FROM eclipse-temurin:25-jdk-alpine@sha256:5ecfde8e5ecde5954ea3721155b345ef56c1d579b940c761318ad4c05959a151 AS builder
WORKDIR /workspace

# Copy build configuration first so dependency resolution is cached independently of
# application source changes.
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY application/build.gradle.kts application/build.gradle.kts
COPY modules/ledger/build.gradle.kts modules/ledger/build.gradle.kts
COPY modules/messaging/build.gradle.kts modules/messaging/build.gradle.kts
COPY modules/notifications/build.gradle.kts modules/notifications/build.gradle.kts
COPY modules/operations/build.gradle.kts modules/operations/build.gradle.kts
COPY modules/orders/build.gradle.kts modules/orders/build.gradle.kts
COPY modules/payments/build.gradle.kts modules/payments/build.gradle.kts
COPY config/ config/

COPY application/src application/src
COPY modules/ledger/src modules/ledger/src
COPY modules/messaging/src modules/messaging/src
COPY modules/notifications/src modules/notifications/src
COPY modules/operations/src modules/operations/src
COPY modules/orders/src modules/orders/src
COPY modules/payments/src modules/payments/src

RUN ./gradlew --no-daemon :application:bootJar

FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0 AS runtime

# Overridden by CI (--build-arg) with the real commit and build time; local builds fall
# back to honest "unknown" values rather than a fabricated revision/date.
ARG IMAGE_REVISION=unknown
ARG IMAGE_CREATED=unknown
ARG IMAGE_VERSION=0.1.0-SNAPSHOT

LABEL org.opencontainers.image.title="ledgerflow" \
    org.opencontainers.image.description="LedgerFlow API, worker, and one-shot Flyway migration image" \
    org.opencontainers.image.source="https://github.com/m3rcury02/ledgerflow" \
    org.opencontainers.image.licenses="NOASSERTION" \
    org.opencontainers.image.version="${IMAGE_VERSION}" \
    org.opencontainers.image.revision="${IMAGE_REVISION}" \
    org.opencontainers.image.created="${IMAGE_CREATED}"

# Removes the base image's font-rendering and PKCS#11-trust package chains. Verified
# (docs/container-hardening.md) that nothing else in the image depends on them (`apk info
# --rdepends`) and that the application — a headless JSON/HTTP API with no AWT, PDF, image,
# or hardware-token usage — starts and serves real traffic without them. ttf-dejavu is the
# "world"-pinned meta-package the upstream image marks explicit; without deleting it too,
# apk silently refuses to remove anything it needs and just patches libexpat/freetype in
# place instead. This closed the HIGH-severity libexpat (CVE-2026-56131/56407/56408,
# pulled in only by fontconfig) and p11-kit/p11-kit-trust (CVE-2026-2100, required by
# nothing) findings a local Trivy scan turned up in the unmodified upstream base image, and
# shrank the package set from 44 to 30 packages.
RUN apk del --no-cache \
    ttf-dejavu font-dejavu fontconfig encodings mkfontscale freetype libfontenc libexpat \
    p11-kit p11-kit-trust

RUN addgroup -S ledgerflow && adduser -S -G ledgerflow -h /app ledgerflow
WORKDIR /app
COPY --from=builder --chown=ledgerflow:ledgerflow \
    /workspace/application/build/libs/ledgerflow.jar /app/ledgerflow.jar

USER ledgerflow:ledgerflow
EXPOSE 8080 8081

ENTRYPOINT ["java", "-jar", "/app/ledgerflow.jar"]
