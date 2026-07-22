# syntax=docker/dockerfile:1
#
# Standalone container for MockPaymentProviderServer (application/src/integrationTest/java/
# com/ledgerflow/testing/payment/), test-only and never packaged in the production
# ledgerflow.jar or image. Used only by the Milestone 4 kind smoke test so the API
# Deployment's real create-order path has a real payment provider to call — see
# docs/kubernetes-deployment.md and performance/compose.perf.yaml, which uses the same fixture over
# a mounted host classpath
# for Compose. This image is self-contained instead (no host-path mount), because a kind
# node's containerd store is separate from the host and does not see host bind mounts by
# default.
#
# MockPaymentProviderServer intentionally binds only 127.0.0.1, so it must run as a sidecar
# container in the same Pod as ledgerflow-api (sharing that Pod's network namespace) — see
# deploy/helm/ledgerflow/templates/deployment-api.yaml.

FROM eclipse-temurin:25-jdk-alpine@sha256:5ecfde8e5ecde5954ea3721155b345ef56c1d579b940c761318ad4c05959a151 AS builder
WORKDIR /workspace

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

RUN ./gradlew --no-daemon :application:exportMockProviderRuntime

FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0 AS runtime

LABEL org.opencontainers.image.title="ledgerflow-mock-payment-provider" \
    org.opencontainers.image.description="Test-only deterministic payment provider fixture for the Milestone 4 kind smoke test; not used in production" \
    org.opencontainers.image.source="https://github.com/m3rcury02/ledgerflow"

RUN addgroup -S mockprovider && adduser -S -G mockprovider -h /app mockprovider
WORKDIR /app
COPY --from=builder --chown=mockprovider:mockprovider \
    /workspace/application/build/mock-provider-runtime/classes /app/classes
COPY --from=builder --chown=mockprovider:mockprovider \
    /workspace/application/build/mock-provider-runtime/libs /app/libs

USER mockprovider:mockprovider
EXPOSE 8090
ENTRYPOINT ["sh", "-c", "exec java -cp /app/classes:/app/libs/* com.ledgerflow.testing.payment.StandaloneMockPaymentProviderServer"]
