variable "environment" {
  description = "Environment name"
  type        = string
}

variable "vpc_id" {
  description = "VPC ID"
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnet IDs"
  type        = list(string)
}

variable "ecs_security_group_id" {
  description = "Security Group ID of the ECS tasks"
  type        = string
}

variable "enable_kafka" {
  description = "Flag to enable Managed Streaming for Apache Kafka (MSK)"
  type        = bool
  default     = false
}
