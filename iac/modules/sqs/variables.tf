variable "queue_name" {
  description = "SQS queue name"
  type        = string
}

variable "dlq_name" {
  description = "SQS dead-letter queue name"
  type        = string
}

variable "timeout_sec" {
  description = "Lambda timeout in seconds — SQS visibility timeout is set to this plus a buffer"
  type        = number
  default     = 300
}
