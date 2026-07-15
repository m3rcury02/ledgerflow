# LedgerFlow Deployment Security Boundaries

- Status: Required production deployment contract
- Last updated: 2026-07-15

## Scope

This document defines the network and Kafka authorization controls assumed by the application. It is not production infrastructure as code. A deployment is not production-ready merely because the application security tests pass; the platform team must implement and verify these boundaries in its chosen load balancer, firewall, service mesh, and network-policy system.

## HTTP listener isolation

LedgerFlow has two listeners:

| Listener | Default | Intended callers | Public routing |
| --- | ---: | --- | --- |
| Application | `8080` | Customer/operator API ingress | Allowed only through the authenticated API ingress |
| Management | `8081` | Kubelet or equivalent health probes and protected monitoring | Prohibited |

`LEDGERFLOW_MANAGEMENT_PORT` configures the management listener. It must differ from `server.port` outside tests. The local dependency stack already uses host port `8081` for Keycloak, so local application examples use `8082`; this local override does not change the production default or trust model.

The application port must never route or proxy `/actuator/**`. The management listener exposes only:

- `/actuator/health/liveness`, returning aggregate status only;
- `/actuator/health/readiness`, returning aggregate status only; and
- `/actuator/prometheus`, for the monitoring network only.

Aggregate health, individual components, health details, environment/configuration, mappings, heap dumps, loggers, shutdown, and `info` are not exposed. The management security chain is defense in depth, not a public authentication boundary. The status endpoints and Prometheus endpoint are reachable without a bearer token on that listener because platform probes and scrapers commonly do not use application JWTs; network isolation is therefore mandatory.

Production review must verify all of the following:

1. No public load balancer, public ingress rule, API gateway route, port-forward service, or internet-facing firewall rule targets the management port.
2. The public service declares only the application port.
3. A separate internal service, if required, selects only the management port and is reachable solely from approved probe and monitoring sources.
4. Deny-by-default ingress applies to the workload. Only the authenticated API ingress may reach the application port; only platform health-probe and monitoring sources may reach the management port.
5. Prometheus uses the management address, not the application address. Scrape transport and authentication between the monitoring plane and workload follow platform policy.
6. Deployment validation fails if both listeners are mapped through one public service or if the management listener is bound to the same port as the application listener.
7. Network controls are tested from both an allowed monitoring source and a denied untrusted workload before production traffic is enabled.

The following Kubernetes `NetworkPolicy` is illustrative. Namespace labels, ingress-controller selectors, node-originated probe behavior, and CNI enforcement differ by platform and must be verified rather than copied blindly.

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: ledgerflow-ingress
  namespace: ledgerflow
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: ledgerflow
  policyTypes: [Ingress]
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              ledgerflow.network/api-ingress: "true"
      ports:
        - protocol: TCP
          port: 8080
    - from:
        - namespaceSelector:
            matchLabels:
              ledgerflow.network/monitoring: "true"
        - namespaceSelector:
            matchLabels:
              ledgerflow.network/health-probes: "true"
      ports:
        - protocol: TCP
          port: 8081
```

## Dependency-probe budget

Readiness checks query PostgreSQL and query Kafka only when a Kafka adapter is enabled. `LEDGERFLOW_HEALTH_PROBE_CACHE_TTL` defaults to `2s` and accepts `250ms` through `10s`. Successful and failed snapshots are cached, and concurrent callers share one in-flight computation. Kafka checks reuse one lifecycle-managed lazy Admin client. Startup validation bypasses the cache so a stale readiness result cannot admit a process.

The platform probe interval must be greater than or equal to the configured cache TTL unless a reviewed capacity test supports a shorter interval. Do not use high-frequency external scraping as a dependency monitor. Liveness never probes PostgreSQL or Kafka; restarting the process cannot repair those dependencies.

## Kafka least privilege

Production Kafka must use TLS/SASL (or the platform's equivalent authenticated transport), provision topics outside the application, disable runtime topic auto-creation, and avoid wildcard topic or cluster-administration privileges.

| Principal or adapter | Required authority | Explicitly unnecessary |
| --- | --- | --- |
| Outbox publisher | Write and Describe on `ledgerflow.payment-captured.v1` | Read main/DLT, Write DLT |
| Notification consumer | Read and Describe main topic plus `ledgerflow-notifications-v1` group | Write main, Read DLT |
| Main-listener DLT recoverer | Write and Describe on `ledgerflow.payment-captured.v1.dlt` | Read DLT, arbitrary-topic write |
| DLT catalog consumer | Read and Describe DLT topic plus `ledgerflow-notifications-dlt-v1` group | Write main or DLT |
| Replay publisher | Write and Describe main topic only | Read main, Write/Read DLT, arbitrary-topic write |
| Readiness probe | Describe cluster or the narrowest broker metadata operation supported by the platform | Topic creation/deletion, ACL administration, offset mutation |

If one deployable process temporarily shares a Kafka principal, its ACL is the union of enabled adapters only. Disabled adapters do not justify retained privileges. Prefer separate workload identities when runtime separation permits it. Notification semantic idempotency limits duplicate effects but does not make unauthorized production of main-topic events safe; authenticated producers and least-privilege ACLs remain required.

## Secrets and production prerequisites

Credentials, trust material, and tokens come from environment-variable injection or an approved secret store, never committed files. Production also requires TLS policy, separate Flyway-owner and non-owner runtime database roles, backup/restore testing, retention decisions, broker/database hardening, and the unresolved quota/idempotency-retention and authenticated replay controls tracked in the MVP ExecPlan.

Local Compose binds services to `127.0.0.1` and is demonstration infrastructure only. Its placeholder credentials, shared database owner, plaintext endpoints, and local vulnerability risk acceptances are not a production deployment design.
