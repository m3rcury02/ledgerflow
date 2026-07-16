# Terraform Validation Evidence

The `infra/terraform` directory contains Infrastructure as Code (IaC) definitions.

- Provisioning covers managed PostgreSQL databases (e.g., RDS) and managed Kafka clusters (e.g., MSK or Confluent Cloud).
- **Validation**: Terraform files are validated using `terraform validate` during pipeline checks to ensure that all required modules and providers are properly configured and syntactically valid before any real resources are provisioned.
