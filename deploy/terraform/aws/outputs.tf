output "alb_dns_name" {
  description = "Public DNS name of the Application Load Balancer."
  value       = aws_lb.this.dns_name
}

output "ecr_repository_url" {
  description = "ECR repository URL to push the application image to before applying."
  value       = aws_ecr_repository.app.repository_url
}

output "ecs_cluster_name" {
  description = "ECS cluster name."
  value       = aws_ecs_cluster.this.name
}

output "rds_endpoint" {
  description = "RDS instance connection endpoint (host:port). No credentials - see rds_secret_arn."
  value       = aws_db_instance.this.endpoint
}

output "rds_database_name" {
  description = "LedgerFlow database name used by the identity bootstrap and migration task."
  value       = aws_db_instance.this.db_name
}

output "rds_bootstrap_username" {
  description = "Bootstrap-only RDS master username."
  value       = aws_db_instance.this.username
}

output "rds_secret_arn" {
  description = "Bootstrap-only RDS-managed master credential ARN. Never injected into an ECS task."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "api_database_secret_arn" {
  description = "Encrypted secret container for the restricted API database identity. Populate out of band; Terraform never stores the value."
  value       = aws_secretsmanager_secret.api_database.arn
}

output "worker_database_secret_arn" {
  description = "Encrypted secret container for the restricted worker database identity. Populate out of band; Terraform never stores the value."
  value       = aws_secretsmanager_secret.worker_database.arn
}

output "migration_database_secret_arn" {
  description = "Encrypted secret container for the Flyway migration-owner identity. Populate out of band; Terraform never stores the value."
  value       = aws_secretsmanager_secret.migration_database.arn
}

output "migration_task_definition_arn" {
  description = "One-shot Flyway ECS task definition. Run and wait for exit code 0 before enabling application services."
  value       = aws_ecs_task_definition.migration.arn
}

output "migration_security_group_id" {
  description = "Security group for the one-shot migration task."
  value       = aws_security_group.migration.id
}

output "private_application_subnet_ids" {
  description = "Private subnet IDs used when launching the one-shot migration task."
  value       = aws_subnet.private_app[*].id
}

output "vpc_id" {
  description = "VPC ID."
  value       = aws_vpc.this.id
}
