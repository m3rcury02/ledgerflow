# syntax=docker/dockerfile:1
#
# Multi-stage build for the LedgerFlow deployable application. There is a single build
# artifact (application/build/libs/ledgerflow.jar); "worker" deployments run this same
# image with different configuration (see docs/plans/portfolio-extension-execplan.md),
# not a separate image.
#
# Extension 3 (production-oriented containers) extends this image with OCI metadata,
# read-only-root-filesystem compatibility, bounded temp storage, and documented JVM
# resource settings. This stage only needs to build and run correctly, non-root.

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

RUN addgroup -S ledgerflow && adduser -S -G ledgerflow -h /app ledgerflow
WORKDIR /app
COPY --from=builder --chown=ledgerflow:ledgerflow \
    /workspace/application/build/libs/ledgerflow.jar /app/ledgerflow.jar

USER ledgerflow:ledgerflow
EXPOSE 8080 8081

ENTRYPOINT ["java", "-jar", "/app/ledgerflow.jar"]
