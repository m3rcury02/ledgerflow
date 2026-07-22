# Dedicated least-privilege roles per service role (api / worker / migration), never shared - the same
# reasoning Milestone 4's Helm chart applied to its two ServiceAccounts (see
# docs/kubernetes-deployment.md): each role's execution role can reach only its own log group,
# the shared ECR repository, and its own database secret; each task role is empty because the
# application itself never calls an AWS API.

data "aws_iam_policy_document" "ecs_tasks_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# --- api -----------------------------------------------------------------

resource "aws_iam_role" "api_execution" {
  name               = "${var.project_name}-${var.environment}-api-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume_role.json
}

data "aws_iam_policy_document" "api_execution" {
  statement {
    sid       = "PullImage"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid    = "PullRepository"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:GetDownloadUrlForLayer",
      "ecr:BatchGetImage",
    ]
    resources = [aws_ecr_repository.app.arn]
  }

  statement {
    sid       = "WriteOwnLogGroup"
    effect    = "Allow"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.api.arn}:*"]
  }

  statement {
    sid       = "ReadDbSecret"
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.api_database.arn]
  }

  statement {
    sid       = "DecryptDbSecret"
    effect    = "Allow"
    actions   = ["kms:Decrypt"]
    resources = [aws_kms_key.logs.arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["secretsmanager.${var.aws_region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "api_execution" {
  name   = "execution"
  role   = aws_iam_role.api_execution.id
  policy = data.aws_iam_policy_document.api_execution.json
}

resource "aws_iam_role" "api_task" {
  name               = "${var.project_name}-${var.environment}-api-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume_role.json
}

# --- worker ----------------------------------------------------------------

resource "aws_iam_role" "worker_execution" {
  name               = "${var.project_name}-${var.environment}-worker-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume_role.json
}

data "aws_iam_policy_document" "worker_execution" {
  statement {
    sid       = "PullImage"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid    = "PullRepository"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:GetDownloadUrlForLayer",
      "ecr:BatchGetImage",
    ]
    resources = [aws_ecr_repository.app.arn]
  }

  statement {
    sid       = "WriteOwnLogGroup"
    effect    = "Allow"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.worker.arn}:*"]
  }

  statement {
    sid       = "ReadDbSecret"
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.worker_database.arn]
  }

  statement {
    sid       = "DecryptDbSecret"
    effect    = "Allow"
    actions   = ["kms:Decrypt"]
    resources = [aws_kms_key.logs.arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["secretsmanager.${var.aws_region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "worker_execution" {
  name   = "execution"
  role   = aws_iam_role.worker_execution.id
  policy = data.aws_iam_policy_document.worker_execution.json
}

resource "aws_iam_role" "worker_task" {
  name               = "${var.project_name}-${var.environment}-worker-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume_role.json
}

# --- one-shot migration ------------------------------------------------------

resource "aws_iam_role" "migration_execution" {
  name               = "${var.project_name}-${var.environment}-migration-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume_role.json
}

data "aws_iam_policy_document" "migration_execution" {
  statement {
    sid       = "PullImage"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid    = "PullRepository"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:GetDownloadUrlForLayer",
      "ecr:BatchGetImage",
    ]
    resources = [aws_ecr_repository.app.arn]
  }

  statement {
    sid       = "WriteOwnLogGroup"
    effect    = "Allow"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.migration.arn}:*"]
  }

  statement {
    sid       = "ReadDbSecret"
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.migration_database.arn]
  }

  statement {
    sid       = "DecryptDbSecret"
    effect    = "Allow"
    actions   = ["kms:Decrypt"]
    resources = [aws_kms_key.logs.arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["secretsmanager.${var.aws_region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "migration_execution" {
  name   = "execution"
  role   = aws_iam_role.migration_execution.id
  policy = data.aws_iam_policy_document.migration_execution.json
}

resource "aws_iam_role" "migration_task" {
  name               = "${var.project_name}-${var.environment}-migration-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume_role.json
}

# --- VPC Flow Logs ----------------------------------------------------------

data "aws_iam_policy_document" "vpc_flow_logs_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["vpc-flow-logs.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "vpc_flow_logs" {
  name               = "${var.project_name}-${var.environment}-vpc-flow-logs"
  assume_role_policy = data.aws_iam_policy_document.vpc_flow_logs_assume_role.json
}

data "aws_iam_policy_document" "vpc_flow_logs" {
  statement {
    sid    = "WriteFlowLogGroup"
    effect = "Allow"
    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents",
      "logs:DescribeLogStreams",
    ]
    resources = ["${aws_cloudwatch_log_group.vpc_flow_logs.arn}:*"]
  }
}

resource "aws_iam_role_policy" "vpc_flow_logs" {
  name   = "write-flow-logs"
  role   = aws_iam_role.vpc_flow_logs.id
  policy = data.aws_iam_policy_document.vpc_flow_logs.json
}
