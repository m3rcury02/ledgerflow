provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = var.project_name
      Environment = var.environment
      Component   = "terraform-state-bootstrap"
      ManagedBy   = "terraform"
    }
  }
}

resource "aws_s3_bucket" "state" {
  #checkov:skip=CKV_AWS_144:Single-region state bucket is sufficient for this design's scope; cross-region replication is a real-deployment enhancement, not a functional requirement of a Terraform state store.
  #checkov:skip=CKV_AWS_18:A dedicated access-log bucket would double this module's footprint for a bucket that holds only Terraform state; a real deployment should audit it via CloudTrail S3 data events instead of a second logging bucket.
  #checkov:skip=CKV2_AWS_62:State objects have no downstream event-driven consumer; versioning plus the noncurrent-version lifecycle rule below is the relevant control for this bucket.
  bucket = var.state_bucket_name

  # Applied once, manually, before the main configuration ever runs `terraform init`; not a
  # resource the main configuration or any automated teardown should delete accidentally.
  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    id     = "expire-noncurrent-state-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 90
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    status = "Enabled"
  }
}

#trivy:ignore:AWS-0132 Same rationale as the DynamoDB lock table's CKV_AWS_119 skip below: a customer-managed key would be a circular bootstrap dependency for a module whose entire purpose is being the minimal prerequisite for the main configuration's own (already-CMK-encrypted) resources. AWS-managed SSE-KMS is still real encryption at rest.
resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket = aws_s3_bucket.state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_policy" "state" {
  bucket = aws_s3_bucket.state.id
  policy = data.aws_iam_policy_document.state_bucket_tls_only.json
}

data "aws_iam_policy_document" "state_bucket_tls_only" {
  statement {
    sid    = "DenyInsecureTransport"
    effect = "Deny"

    principals {
      type        = "AWS"
      identifiers = ["*"]
    }

    actions   = ["s3:*"]
    resources = [aws_s3_bucket.state.arn, "${aws_s3_bucket.state.arn}/*"]

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_dynamodb_table" "lock" {
  #checkov:skip=CKV_AWS_119:Table stores only lock coordination metadata (no sensitive data); AWS-owned-key encryption is already enabled. A customer-managed key would create a circular bootstrap dependency for a module whose entire purpose is being the minimal prerequisite.
  name         = var.lock_table_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled = true
  }
}
