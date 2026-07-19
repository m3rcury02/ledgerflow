# Partial backend configuration: deliberately no bucket/key/region/dynamodb_table values
# here, so no account-specific identifiers are committed to the repository. Supply the rest
# at `terraform init` time, either with -backend-config flags or a gitignored backend.hcl
# (see docs/aws-terraform-design.md, "Remote state bootstrap"):
#
#   terraform init \
#     -backend-config="bucket=<state bucket from the bootstrap module's output>" \
#     -backend-config="key=ledgerflow/aws/terraform.tfstate" \
#     -backend-config="region=<aws_region>" \
#     -backend-config="dynamodb_table=<lock table from the bootstrap module's output>" \
#     -backend-config="encrypt=true"
terraform {
  backend "s3" {}
}
