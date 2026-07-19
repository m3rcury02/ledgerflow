# Single shared CMK for CloudWatch Logs and the ECR repository (ecr.tf) - one key covering
# this design's at-rest encryption needs rather than a key per resource, deliberately, to
# keep the KMS footprint proportionate to a portfolio-scale design.
resource "aws_kms_key" "logs" {
  description         = "Encrypts CloudWatch Logs and the ECR repository for ${var.project_name}-${var.environment}."
  enable_key_rotation = true

  policy = data.aws_iam_policy_document.logs_kms_key.json
}

resource "aws_kms_alias" "logs" {
  name          = "alias/${var.project_name}-${var.environment}-app"
  target_key_id = aws_kms_key.logs.key_id
}

data "aws_caller_identity" "current" {}

data "aws_iam_policy_document" "logs_kms_key" {
  #checkov:skip=CKV_AWS_356:This is the AWS-recommended default KMS key policy shape: grant the account root full administrative control so IAM policies can also govern the key. resources=["*"] is correct here because a key policy's Resource element always self-targets the key it is attached to, not a cross-resource wildcard.
  #checkov:skip=CKV_AWS_109:Same root cause as CKV_AWS_356 - the account-root admin statement is the standard AWS default key policy grant, not an unconstrained cross-account exposure.
  #checkov:skip=CKV_AWS_111:Same root cause as CKV_AWS_356 - see above.
  statement {
    sid       = "AllowAccountRootAdmin"
    effect    = "Allow"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }

  statement {
    sid    = "AllowCloudWatchLogsUse"
    effect = "Allow"
    actions = [
      "kms:Encrypt*",
      "kms:Decrypt*",
      "kms:ReEncrypt*",
      "kms:GenerateDataKey*",
      "kms:Describe*",
    ]
    resources = ["*"]

    principals {
      type        = "Service"
      identifiers = ["logs.${var.aws_region}.amazonaws.com"]
    }

    condition {
      test     = "ArnLike"
      variable = "kms:EncryptionContext:aws:logs:arn"
      values   = ["arn:aws:logs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:*"]
    }
  }

  statement {
    sid    = "AllowEcrUse"
    effect = "Allow"
    actions = [
      "kms:Encrypt*",
      "kms:Decrypt*",
      "kms:ReEncrypt*",
      "kms:GenerateDataKey*",
      "kms:Describe*",
    ]
    resources = ["*"]

    principals {
      type        = "Service"
      identifiers = ["ecr.amazonaws.com"]
    }
  }
}

resource "aws_cloudwatch_log_group" "api" {
  name              = "/ecs/${var.project_name}-${var.environment}/api"
  retention_in_days = var.log_retention_days
  kms_key_id        = aws_kms_key.logs.arn
}

resource "aws_cloudwatch_log_group" "worker" {
  name              = "/ecs/${var.project_name}-${var.environment}/worker"
  retention_in_days = var.log_retention_days
  kms_key_id        = aws_kms_key.logs.arn
}

resource "aws_cloudwatch_log_group" "vpc_flow_logs" {
  name              = "/vpc/${var.project_name}-${var.environment}/flow-logs"
  retention_in_days = var.log_retention_days
  kms_key_id        = aws_kms_key.logs.arn
}
