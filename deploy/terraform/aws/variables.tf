variable "aws_region" {
  description = "AWS region for every resource in this configuration."
  type        = string
  default     = "ap-south-1"
}

variable "project_name" {
  description = "Project identifier used in resource names and tags."
  type        = string
  default     = "ledgerflow"
}

variable "environment" {
  description = "Deployment environment identifier used in resource names and tags."
  type        = string
  default     = "dev"
}

# --- Networking -------------------------------------------------------------

variable "vpc_cidr" {
  description = "CIDR block for the VPC."
  type        = string
  default     = "10.0.0.0/16"
}

variable "public_subnet_cidrs" {
  description = "CIDR blocks for the two public subnets (one per AZ): ALB only."
  type        = list(string)
  default     = ["10.0.0.0/24", "10.0.1.0/24"]
}

variable "private_app_subnet_cidrs" {
  description = "CIDR blocks for the two private application subnets (one per AZ): ECS tasks."
  type        = list(string)
  default     = ["10.0.10.0/24", "10.0.11.0/24"]
}

variable "private_data_subnet_cidrs" {
  description = "CIDR blocks for the two private data subnets (one per AZ): RDS only, no route beyond the VPC."
  type        = list(string)
  default     = ["10.0.20.0/24", "10.0.21.0/24"]
}

# --- Container image ---------------------------------------------------------

variable "container_image_tag" {
  description = <<-EOT
    Immutable image tag to deploy (e.g. a git commit SHA). No default: the ECR repository
    starts empty and "latest" cannot be reused once pushed under IMAGE_TAG_MUTABILITY =
    IMMUTABLE, so a real, deployment-specific tag must be supplied at apply time.
  EOT
  type        = string

  validation {
    condition     = length(var.container_image_tag) > 0
    error_message = "container_image_tag must be set explicitly to a real, pushed image tag."
  }
}

# --- ECS: api service ---------------------------------------------------------

variable "api_cpu" {
  description = "Fargate task CPU units for the api service (256 = 0.25 vCPU)."
  type        = number
  default     = 512
}

variable "api_memory" {
  description = "Fargate task memory (MiB) for the api service."
  type        = number
  default     = 1024
}

variable "api_desired_count" {
  description = "Initial desired task count for the api service (Application Auto Scaling adjusts this at runtime)."
  type        = number
  default     = 2
}

variable "api_min_count" {
  description = "Minimum api task count for Application Auto Scaling. Matches Milestone 4's Helm chart HPA minReplicas."
  type        = number
  default     = 2
}

variable "api_max_count" {
  description = "Maximum api task count for Application Auto Scaling. Matches Milestone 4's Helm chart HPA maxReplicas."
  type        = number
  default     = 5
}

variable "api_target_cpu_percent" {
  description = "Target-tracking CPU utilization percent for api autoscaling. Matches Milestone 4's Helm chart HPA target."
  type        = number
  default     = 50
}

# --- ECS: worker service (background jobs; no public ingress, no autoscaling) ------------

variable "worker_cpu" {
  description = "Fargate task CPU units for the worker service."
  type        = number
  default     = 512
}

variable "worker_memory" {
  description = "Fargate task memory (MiB) for the worker service."
  type        = number
  default     = 1024
}

variable "worker_desired_count" {
  description = "Fixed task count for the worker service (no autoscaling: background-job replicas, not request/response capacity)."
  type        = number
  default     = 2
}

# --- RDS ------------------------------------------------------------------

variable "db_engine_version" {
  description = <<-EOT
    PostgreSQL major version. AWS RDS resolves this to the latest supported minor version at
    creation time. Verify current availability with `aws rds describe-db-engine-versions
    --engine postgres` before ever applying this configuration - supported major/minor
    versions change over time and this value is not (and cannot be) checked by `terraform
    validate`.
  EOT
  type        = string
  default     = "16"
}

variable "db_instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_allocated_storage" {
  description = "RDS allocated storage, in GiB."
  type        = number
  default     = 20
}

variable "db_multi_az" {
  description = "Whether RDS runs Multi-AZ (standby in the second AZ). Matches this design's two-AZ HA intent."
  type        = bool
  default     = true
}

variable "db_backup_retention_days" {
  description = "RDS automated backup retention, in days."
  type        = number
  default     = 7
}

variable "db_deletion_protection" {
  description = <<-EOT
    Whether RDS deletion protection is enabled. Defaults to true, matching production
    practice; must be explicitly disabled (`-var db_deletion_protection=false`) before
    `terraform destroy` can succeed - see docs/aws-terraform-design.md's teardown
    instructions.
  EOT
  type        = bool
  default     = true
}

variable "db_name" {
  description = "Initial database name created on the RDS instance."
  type        = string
  default     = "ledgerflow"
}

variable "db_username" {
  description = "RDS master username. The password is never a Terraform variable: it is generated and stored by RDS itself in Secrets Manager (manage_master_user_password)."
  type        = string
  default     = "ledgerflow"
}

# --- Observability ----------------------------------------------------------

variable "log_retention_days" {
  description = <<-EOT
    CloudWatch Logs retention for ECS/VPC-flow-log logs, in days. Defaults to 400 (just over
    a year) rather than a shorter dev-tier value: LedgerFlow is a ledger/payments system, and
    a full year of application and network-flow logs is a reasonable baseline for audit and
    incident-investigation purposes, not merely a value chosen to satisfy a scanner.
  EOT
  type        = number
  default     = 400
}

variable "enable_container_insights" {
  description = "Whether to enable ECS Container Insights on the cluster."
  type        = bool
  default     = true
}

# --- TLS ----------------------------------------------------------------

variable "acm_certificate_arn" {
  description = <<-EOT
    ACM certificate ARN for the ALB's HTTPS listener. Empty by default (no real domain or
    certificate exists for this never-applied design); when set, an HTTPS listener is
    created and HTTP redirects to it. See docs/aws-terraform-design.md.
  EOT
  type        = string
  default     = ""
}

# --- Out-of-scope dependency placeholders ------------------------------------

variable "kafka_bootstrap_servers" {
  description = <<-EOT
    Kafka bootstrap servers connection string. Placeholder: Amazon MSK is explicitly out of
    scope for this milestone (see docs/aws-terraform-design.md, "Out of scope"). A real
    deployment must replace this with a real MSK (or self-managed Kafka) endpoint before the
    application can start.
  EOT
  type        = string
  default     = "CHANGEME-kafka-out-of-scope.internal:9092"
}

variable "oauth2_issuer_uri" {
  description = <<-EOT
    OAuth2/OIDC issuer URI. Placeholder: an identity provider (self-hosted Keycloak or Amazon
    Cognito) is explicitly out of scope for this milestone (see
    docs/aws-terraform-design.md, "Out of scope"). A real deployment must replace this with a
    real issuer URI before the application can start.
  EOT
  type        = string
  default     = "https://CHANGEME-identity-provider-out-of-scope.internal/realms/ledgerflow"
}
