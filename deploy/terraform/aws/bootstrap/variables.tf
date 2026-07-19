variable "aws_region" {
  description = "AWS region to create the state bucket and lock table in."
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

variable "state_bucket_name" {
  description = <<-EOT
    Globally-unique S3 bucket name for Terraform remote state. S3 bucket names are unique
    across all of AWS, not just this account, so no default is provided — supply one at
    apply time, e.g. "ledgerflow-tfstate-<your-account-id-or-suffix>".
  EOT
  type        = string

  validation {
    condition     = length(var.state_bucket_name) > 0
    error_message = "state_bucket_name must be set explicitly; it cannot default to a shared, potentially colliding name."
  }
}

variable "lock_table_name" {
  description = "DynamoDB table name used for Terraform state locking."
  type        = string
  default     = "ledgerflow-tfstate-lock"
}
