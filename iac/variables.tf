variable "app_code" {
  description = "Application code used as prefix for all AWS asset names"
  type        = string
  default     = "cpd"
}

variable "env" {
  description = "Deployment environment (dev, staging, prod)"
  type        = string
  default     = "dev"
}

variable "lambda_zip_path" {
  description = "Path to the Lambda deployment package (relative to terraform root)"
  type        = string
  default     = "../../../dist/cpd-ai.zip"
}

variable "glue_script_path" {
  description = "Local path to the Glue PySpark ETL script"
  type        = string
  default     = "../../main/python/credit_cards.py"
}

variable "glue_worker_type" {
  description = "Glue job worker type (G.025X, G.1X, G.2X)"
  type        = string
  default     = "G.1X"
}

variable "glue_num_workers" {
  description = "Number of Glue DPU workers"
  type        = number
  default     = 2
}

variable "lambda_memory_mb" {
  description = "Lambda memory in MB"
  type        = number
  default     = 512
}

variable "lambda_timeout_sec" {
  description = "Lambda timeout in seconds (SQS visibility timeout must be >= this)"
  type        = number
  default     = 300
}
