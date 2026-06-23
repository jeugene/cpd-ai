output "function_arn" {
  value = aws_lambda_function.sqs_processor.arn
}

output "function_name" {
  value = aws_lambda_function.sqs_processor.function_name
}

output "role_arn" {
  value = aws_iam_role.lambda.arn
}
