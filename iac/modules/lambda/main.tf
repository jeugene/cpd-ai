resource "aws_cloudwatch_log_group" "lambda" {
  name              = "/aws/lambda/${var.function_name}"
  retention_in_days = 30
}

resource "aws_lambda_function" "sqs_processor" {
  function_name = var.function_name
  description   = "Processes S3 data events from SQS, upserts records into Iceberg via Glue catalog"
  role          = var.role_arn
  handler       = "sqs_to_s3.handler"
  runtime       = "python3.12"
  timeout       = var.timeout_sec
  memory_size   = var.memory_mb

  filename         = var.zip_path
  source_code_hash = filebase64sha256(var.zip_path)

  environment {
    variables = {
      APP_CODE = var.app_code
      AWS_ENV  = var.env
    }
  }

  depends_on = [aws_cloudwatch_log_group.lambda]
}

resource "aws_lambda_event_source_mapping" "sqs" {
  event_source_arn                   = var.sqs_arn
  function_name                      = aws_lambda_function.sqs_processor.arn
  batch_size                         = 10
  maximum_batching_window_in_seconds = 30
  enabled                            = true

  function_response_types = ["ReportBatchItemFailures"]

  scaling_config {
    maximum_concurrency = 5
  }
}
