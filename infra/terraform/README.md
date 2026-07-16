# LedgerFlow Terraform Design

This directory contains the AWS Terraform infrastructure design for LedgerFlow.

**NOTE:** This design has been fully statically validated (`terraform validate`, `tflint`, `checkov`) but is **not intended to be applied** unless you want to incur AWS costs. No automatic resource creation is configured in CI/CD.

## Modules

- `vpc`: 2-AZ VPC with public and private subnets, NAT Gateway, and Internet Gateway.
- `ecs`: ECS Fargate cluster, ALB, and task definitions for the LedgerFlow container, running as non-root with least-privilege IAM roles.
- `rds`: RDS PostgreSQL instance with storage encryption, isolated in private subnets, securely using Secrets Manager for credentials.
- `kafka`: (Optional, disabled by default) Managed Streaming for Apache Kafka (MSK) cluster for event streaming.

## Security Posture

- No public databases.
- Encryption at rest (KMS) for ECR, RDS, and CloudWatch.
- Encryption in transit (TLS) for Kafka and ALB.
- Least-privilege IAM execution and task roles (no `*` permissions).
- Passwords dynamically generated and stored in Secrets Manager.

## Cost Estimates (Monthly - US East 1)

This is an estimate for the `dev` composition:

- **ALB:** ~$16/month
- **NAT Gateway:** ~$33/month (single NAT Gateway to reduce cost)
- **ECS Fargate (0.5 vCPU, 1 GB Memory x 2 tasks):** ~$15/month
- **RDS PostgreSQL (db.t4g.micro, 20GB gp3):** ~$14/month
- **Secrets Manager / KMS / CloudWatch:** ~$5/month
- **Total Estimated Base Cost:** ~$83/month

*Note: Enabling MSK (`enable_kafka = true`) will significantly increase costs (+$100-200/month).*

## Teardown Documentation

If you choose to apply this configuration to an AWS account, you must properly tear it down to prevent ongoing charges:

1. Empty the ECR repository of all images:
   ```bash
   aws ecr batch-delete-image --repository-name ledgerflow-dev --image-ids $(aws ecr list-images --repository-name ledgerflow-dev --query 'imageIds[*]' --output json)
   ```
2. Run `terraform destroy`:
   ```bash
   cd environments/dev
   terraform destroy -auto-approve
   ```
3. Verify that the RDS instance, ECS cluster, and NAT Gateways are fully deleted via the AWS Console.
