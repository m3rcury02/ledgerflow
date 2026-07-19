terraform {
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # Deliberately local state: this module creates the S3 bucket and DynamoDB table that the
  # main configuration's own remote state depends on, so it cannot depend on that same
  # backend itself (see docs/aws-terraform-design.md, "Remote state bootstrap").
}
