variable "function_name" {
  description = "Lambda function name; log group is derived from it"
  type        = string
}

variable "role_arn" {
  description = "IAM role ARN for the Lambda function"
  type        = string
}

variable "app_code" {
  type = string
}

variable "env" {
  type = string
}

variable "zip_path" {
  description = "Path to the Lambda deployment zip"
  type        = string
}

variable "sqs_arn" {
  description = "ARN of the SQS queue to consume"
  type        = string
}

variable "memory_mb" {
  type    = number
  default = 512
}

variable "timeout_sec" {
  type    = number
  default = 300
}
