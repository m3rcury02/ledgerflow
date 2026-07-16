# Terraform Validation Evidence

The `infra/terraform` directory contains the Infrastructure as Code (IaC) design for LedgerFlow on AWS.

## Design Highlights

- **VPC & Networking**: 2-AZ VPC with public and private subnets, minimal NAT Gateways for cost optimization, and strict security groups.
- **Compute (ECS Fargate)**: Application Load Balancer with TLS/HTTPS enforcement, ECS Fargate tasks running without root privileges, least-privilege IAM Execution and Task roles.
- **Database (RDS)**: PostgreSQL instance in private subnets with storage encryption and securely managed master credentials in AWS Secrets Manager.
- **Event Streaming**: Optional MSK (Kafka) cluster module behind an explicit disabled-by-default feature flag.
- **State & Tags**: Configured with a remote state backend design and comprehensive cost/ownership tagging via default provider tags.

## Validation Status

**CRITICAL NOTE: This design has been thoroughly validated statically but was NEVER applied, and no cloud resources were created.** 

The repository includes an automated CI pipeline that validates the Terraform files using:
1. `terraform fmt -check -recursive`
2. `terraform init -backend=false`
3. `terraform validate`
4. `tflint`
5. `checkov` (with a soft-fail configuration to surface best practices like IAM DB auth, flow logs, and WAF without blocking development workflows).
