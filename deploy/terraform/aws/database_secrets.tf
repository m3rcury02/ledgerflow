# Terraform owns only the encrypted secret containers and access policy. Secret values are
# deliberately populated by the privileged database-identity bootstrap procedure after RDS exists;
# putting generated passwords in aws_secretsmanager_secret_version resources would persist them in
# Terraform state. API, worker, and migration credentials are never shared.

resource "aws_secretsmanager_secret" "api_database" {
  #checkov:skip=CKV2_AWS_57:LF-EXT-R009 records why database-aware automatic rotation cannot be truthfully claimed by this never-applied design; manual coordinated rotation is mandatory until a tested rotation Lambda/workflow exists.
  name_prefix             = "${var.project_name}-${var.environment}-api-db-"
  description             = "Restricted LedgerFlow API PostgreSQL credential. JSON keys: username, password."
  kms_key_id              = aws_kms_key.logs.arn
  recovery_window_in_days = 30
}

resource "aws_secretsmanager_secret" "worker_database" {
  #checkov:skip=CKV2_AWS_57:LF-EXT-R009 records why database-aware automatic rotation cannot be truthfully claimed by this never-applied design; manual coordinated rotation is mandatory until a tested rotation Lambda/workflow exists.
  name_prefix             = "${var.project_name}-${var.environment}-worker-db-"
  description             = "Restricted LedgerFlow worker PostgreSQL credential. JSON keys: username, password."
  kms_key_id              = aws_kms_key.logs.arn
  recovery_window_in_days = 30
}

resource "aws_secretsmanager_secret" "migration_database" {
  #checkov:skip=CKV2_AWS_57:LF-EXT-R009 records why database-aware automatic rotation cannot be truthfully claimed by this never-applied design; manual coordinated rotation is mandatory until a tested rotation Lambda/workflow exists.
  name_prefix             = "${var.project_name}-${var.environment}-migration-db-"
  description             = "LedgerFlow Flyway migration-owner PostgreSQL credential. JSON keys: username, password."
  kms_key_id              = aws_kms_key.logs.arn
  recovery_window_in_days = 30
}
