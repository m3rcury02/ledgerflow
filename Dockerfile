# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build

# Copy gradle wrapper and config
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle/

# Ensure gradlew is executable
RUN chmod +x gradlew

# Copy source code
COPY application application/
COPY modules modules/
COPY config config/

# Build application without running tests
RUN ./gradlew --no-daemon :application:bootJar -x test -x integrationTest -x architectureTest -x checkstyleMain -x checkstyleTest --console=plain

FROM eclipse-temurin:25-jre-alpine AS runtime

# OCI Labels
LABEL org.opencontainers.image.title="LedgerFlow" \
      org.opencontainers.image.description="LedgerFlow Portfolio Edition Modular Monolith" \
      org.opencontainers.image.source="https://github.com/m3rcury02/ledgerflow"

# Create a non-root user
RUN addgroup -S ledgerflow && adduser -S ledgerflow -G ledgerflow

WORKDIR /app

# Bounded writable temporary directory for read-only root fs compatibility
VOLUME /tmp

# Copy built artifact from builder
COPY --from=builder --chown=ledgerflow:ledgerflow /build/application/build/libs/ledgerflow.jar app.jar

# Switch to non-root user
USER ledgerflow

# Container-aware JVM defaults
ENV JAVA_OPTS="-XX:InitialRAMPercentage=40.0 -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Exec form for graceful shutdown (passes SIGTERM correctly to the JVM)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
