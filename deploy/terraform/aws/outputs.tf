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

output "rds_secret_arn" {
  description = "Secrets Manager ARN of the RDS-managed master credential."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "vpc_id" {
  description = "VPC ID."
  value       = aws_vpc.this.id
}
