resource "aws_sqs_queue" "dlq" {
  name                      = var.dlq_name
  message_retention_seconds = 1209600 # 14 days
}

resource "aws_sqs_queue" "data_notify" {
  name = var.queue_name

  # Visibility timeout must be >= Lambda timeout to prevent double-processing
  visibility_timeout_seconds = var.timeout_sec + 60

  message_retention_seconds = 86400 # 1 day — S3 events are replayed from source if needed

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq.arn
    maxReceiveCount     = 3
  })
}
