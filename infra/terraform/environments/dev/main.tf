terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.0"
    }
  }

  # Remote state backend design
  # Uncomment and configure for real environments
  # backend "s3" {
  #   bucket         = "ledgerflow-terraform-state-dev"
  #   key            = "dev/terraform.tfstate"
  #   region         = "us-east-1"
  #   dynamodb_table = "ledgerflow-terraform-locks-dev"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "LedgerFlow"
      Environment = var.environment
      Owner       = "PlatformTeam"
      CostCenter  = "12345"
      ManagedBy   = "Terraform"
    }
  }
}

module "vpc" {
  source = "../../modules/vpc"

  environment     = var.environment
  vpc_cidr        = var.vpc_cidr
  public_subnets  = var.public_subnets
  private_subnets = var.private_subnets
  azs             = var.azs
}

module "rds" {
  source = "../../modules/rds"

  environment           = var.environment
  vpc_id                = module.vpc.vpc_id
  private_subnet_ids    = module.vpc.private_subnet_ids
  ecs_security_group_id = module.ecs.ecs_security_group_id
}

module "ecs" {
  source = "../../modules/ecs"

  environment                  = var.environment
  vpc_id                       = module.vpc.vpc_id
  public_subnet_ids            = module.vpc.public_subnet_ids
  private_subnet_ids           = module.vpc.private_subnet_ids
  database_url_secret_arn      = module.rds.db_password_secret_arn # Simplified for demo
  database_username_secret_arn = module.rds.db_password_secret_arn # Simplified for demo
  database_password_secret_arn = module.rds.db_password_secret_arn
}

module "kafka" {
  source = "../../modules/kafka"

  environment           = var.environment
  vpc_id                = module.vpc.vpc_id
  private_subnet_ids    = module.vpc.private_subnet_ids
  ecs_security_group_id = module.ecs.ecs_security_group_id
  enable_kafka          = var.enable_kafka
}
