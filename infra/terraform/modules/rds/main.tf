resource "aws_db_subnet_group" "main" {
  name       = "ledgerflow-db-subnet-group-${var.environment}"
  subnet_ids = var.private_subnet_ids

  tags = {
    Name = "ledgerflow-db-subnet-group-${var.environment}"
  }
}

resource "aws_security_group" "rds" {
  name        = "ledgerflow-rds-sg-${var.environment}"
  description = "Allow inbound traffic from ECS tasks"
  vpc_id      = var.vpc_id

  ingress {
    description     = "PostgreSQL from ECS"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [var.ecs_security_group_id]
  }

  egress {
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "random_password" "db_password" {
  length           = 16
  special          = true
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

resource "aws_db_instance" "main" {
  identifier        = "ledgerflow-db-${var.environment}"
  engine            = "postgres"
  engine_version    = "16"
  instance_class    = "db.t4g.micro" # Cost-effective for dev
  allocated_storage = 20
  storage_type      = "gp3"
  db_name           = "ledgerflow"
  username          = "ledgerflow_admin"
  password          = random_password.db_password.result

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  publicly_accessible = false # Requirement: no public database
  storage_encrypted   = true

  skip_final_snapshot = var.environment == "dev" ? true : false

  # Checkov best practices usually recommend IAM DB Authentication
  iam_database_authentication_enabled = true
}

resource "aws_secretsmanager_secret" "db_password" {
  name                    = "ledgerflow/db-password-${var.environment}"
  description             = "Database master password"
  recovery_window_in_days = 0 # Force delete without recovery for dev
}

resource "aws_secretsmanager_secret_version" "db_password" {
  secret_id     = aws_secretsmanager_secret.db_password.id
  secret_string = random_password.db_password.result
}

# In a real environment, you might have separate migration and runtime credentials.
# Here we just output the master credentials which can be used for migration.
