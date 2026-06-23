variable "lambda_function_name" {
  description = "Lambda function name; used to name the Lambda IAM role/policy and scope CloudWatch log ARNs"
  type        = string
}

variable "glue_job_name" {
  description = "Glue job name; used to name the Glue IAM role/policy"
  type        = string
}

variable "aws_region" {
  type = string
}

variable "account_id" {
  type = string
}

variable "app_code" {
  description = "Application code; used to scope Glue catalog resource ARNs in the Lambda policy"
  type        = string
}

variable "s3_bucket_arn" {
  description = "ARN of the data S3 bucket"
  type        = string
}

variable "sqs_arn" {
  description = "ARN of the SQS queue the Lambda consumes"
  type        = string
}
