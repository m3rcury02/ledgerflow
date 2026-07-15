# ADR 0010: Isolate Management Endpoints and Bound Dependency Probes

- Status: Accepted
- Date: 2026-07-15

## Context

Actuator health endpoints shared the application listener and application security chain. Readiness performed PostgreSQL and optionally Kafka I/O, and each Kafka probe allocated a new Admin client. A public caller could therefore turn unauthenticated health traffic into connection and thread pressure. Authentication alone would not define the platform network boundary needed by kubelet probes and monitoring.

## Decision

Run Actuator on a configurable management port distinct from the application port. The application security chain has no Actuator permit rules. A management-context security chain exposes only status-only liveness/readiness and Prometheus; aggregate health, component details, `info`, and every other endpoint are denied or disabled.

The management port is not a public interface. Deployment must route it only from approved health-probe and monitoring sources and must expose only the application port through public ingress. `docs/deployment-security.md` is the required deployment contract.

Readiness shares one in-flight dependency computation and caches both success and failure for a bounded configurable TTL, two seconds by default. PostgreSQL queries retain an explicit timeout. Kafka probes reuse one lifecycle-managed, lazily created Admin client. Startup validation invokes the uncached dependency probe.

## Consequences

- Probe bursts cannot multiply dependency work within one TTL, and Kafka clients are not created per request.
- Readiness can be stale for at most the configured short TTL; startup validation remains fresh.
- Existing probes and scrapers must move from the application address to the management address.
- The management chain is defense in depth. A deployment that publicly exposes the management listener violates this ADR even though health details are suppressed.
- Local developers must override the management port when it conflicts with Keycloak's host port.

## Alternatives considered

- Keeping Actuator on the application port with an IP filter was rejected because application proxies and forwarded addresses are an unreliable network authorization boundary.
- Requiring customer OAuth tokens for kubelet health checks was rejected because it couples process probing to identity-provider availability and token distribution.
- Removing dependency readiness was rejected because traffic should not be routed to an instance that cannot reach required persistence or enabled messaging dependencies.
