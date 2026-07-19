output "state_bucket_name" {
  description = "S3 bucket name to use as `bucket` in the main configuration's backend config."
  value       = aws_s3_bucket.state.id
}

output "lock_table_name" {
  description = "DynamoDB table name to use as `dynamodb_table` in the main configuration's backend config."
  value       = aws_dynamodb_table.lock.name
}
