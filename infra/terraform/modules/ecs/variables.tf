variable "environment" {
  description = "Environment name"
  type        = string
}

variable "vpc_id" {
  description = "VPC ID"
  type        = string
}

variable "public_subnet_ids" {
  description = "Public subnet IDs"
  type        = list(string)
}

variable "private_subnet_ids" {
  description = "Private subnet IDs"
  type        = list(string)
}

variable "database_url_secret_arn" {
  description = "ARN of the Secrets Manager secret for the database URL"
  type        = string
}

variable "database_username_secret_arn" {
  description = "ARN of the Secrets Manager secret for the database username"
  type        = string
}

variable "database_password_secret_arn" {
  description = "ARN of the Secrets Manager secret for the database password"
  type        = string
}
