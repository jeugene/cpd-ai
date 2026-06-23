output "queue_arn" {
  value = aws_sqs_queue.data_notify.arn
}

output "queue_url" {
  value = aws_sqs_queue.data_notify.url
}

output "queue_id" {
  value = aws_sqs_queue.data_notify.id
}

output "dlq_arn" {
  value = aws_sqs_queue.dlq.arn
}
