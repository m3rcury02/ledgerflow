# Kubernetes Evidence

The `deploy/helm/ledgerflow` directory contains the Helm chart for deploying LedgerFlow into a Kubernetes cluster.

- Deployments are modeled to enforce a strict boundary between the public Application port (`8080`) and the private Management port (`8082`).
- Configuration Maps and Secrets are used to inject runtime environment variables.
- Resource Quotas are established for memory and CPU to prevent runaway resource consumption, matching the limits defined for the local Compose setup (max 5 GiB memory, 7.75 CPUs for all services).
- **Validation**: Standard YAML linting and validation can be applied to ensure these manifests are syntactically and semantically correct prior to any cluster deployment.
