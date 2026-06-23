output "s3_data_bucket" {
  description = "Name of the S3 data bucket"
  value       = module.s3.bucket_name
}

output "sqs_queue_url" {
  description = "URL of the SQS data notification queue"
  value       = module.sqs.queue_url
}

output "sqs_dlq_arn" {
  description = "ARN of the dead-letter queue"
  value       = module.sqs.dlq_arn
}

output "lambda_function_name" {
  description = "Name of the Lambda SQS processor"
  value       = module.lambda.function_name
}

output "lambda_function_arn" {
  description = "ARN of the Lambda SQS processor"
  value       = module.lambda.function_arn
}

output "glue_job_name" {
  description = "Name of the Glue credit-cards ETL job"
  value       = module.glue.job_name
}

output "glue_catalog_database" {
  description = "Name of the Glue Data Catalog database"
  value       = module.glue.database_name
}
