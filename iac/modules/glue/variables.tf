variable "job_name" {
  description = "Glue job name; catalog database name is derived from it"
  type        = string
}

variable "role_arn" {
  description = "IAM role ARN for the Glue job"
  type        = string
}

variable "s3_bucket_id" {
  description = "ID (name) of the data S3 bucket"
  type        = string
}

variable "script_local_path" {
  description = "Local filesystem path to credit_cards.py (uploaded to S3 on plan/apply)"
  type        = string
}

variable "worker_type" {
  type    = string
  default = "G.1X"
}

variable "num_workers" {
  type    = number
  default = 2
}
