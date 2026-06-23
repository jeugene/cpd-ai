data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  account_id           = data.aws_caller_identity.current.account_id
  aws_region           = data.aws_region.current.name
  s3_bucket_name       = "${var.app_code}-data-${var.env}-${local.aws_region}-${local.account_id}"
  sqs_queue_name       = "${var.app_code}-data-${var.env}-notify"
  sqs_dlq_name         = "${var.app_code}-data-${var.env}-notify-dlq"
  lambda_function_name = "${var.app_code}-lambda-${var.env}-sqs-consumer"
  glue_job_name        = "${var.app_code}-glue-${var.env}-credit-cards-etl"
}

# ── S3 ─────────────────────────────────────────────────────────────────────────

module "s3" {
  source      = "./modules/s3"
  bucket_name = local.s3_bucket_name
}

# ── SQS ────────────────────────────────────────────────────────────────────────

module "sqs" {
  source      = "./modules/sqs"
  queue_name  = local.sqs_queue_name
  dlq_name    = local.sqs_dlq_name
  timeout_sec = var.lambda_timeout_sec
}

# SQS queue policy — must be a root resource to reference both modules without a circular dep.
data "aws_iam_policy_document" "sqs_s3_send" {
  statement {
    sid    = "AllowS3SendMessage"
    effect = "Allow"
    principals {
      type        = "Service"
      identifiers = ["s3.amazonaws.com"]
    }
    actions   = ["sqs:SendMessage"]
    resources = [module.sqs.queue_arn]
    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [module.s3.bucket_arn]
    }
  }
}

resource "aws_sqs_queue_policy" "data_notify" {
  queue_url = module.sqs.queue_url
  policy    = data.aws_iam_policy_document.sqs_s3_send.json
}

# S3 bucket notification — also kept at root level to avoid the circular dep.
resource "aws_s3_bucket_notification" "data" {
  bucket = module.s3.bucket_id

  queue {
    queue_arn     = module.sqs.queue_arn
    events        = ["s3:ObjectCreated:*"]
    filter_prefix = "data/"
  }

  depends_on = [aws_sqs_queue_policy.data_notify]
}

# ── IAM ────────────────────────────────────────────────────────────────────────

module "iam" {
  source               = "./modules/iam"
  lambda_function_name = local.lambda_function_name
  glue_job_name        = local.glue_job_name
  aws_region           = local.aws_region
  account_id           = local.account_id
  app_code             = var.app_code
  s3_bucket_arn        = module.s3.bucket_arn
  sqs_arn              = module.sqs.queue_arn
}

# ── Lambda ──────────────────────────────────────────────────────────────────────

module "lambda" {
  source        = "./modules/lambda"
  function_name = local.lambda_function_name
  role_arn      = module.iam.lambda_role_arn
  app_code      = var.app_code
  env           = var.env
  zip_path      = var.lambda_zip_path
  sqs_arn       = module.sqs.queue_arn
  memory_mb     = var.lambda_memory_mb
  timeout_sec   = var.lambda_timeout_sec
}

# ── Glue ───────────────────────────────────────────────────────────────────────

module "glue" {
  source            = "./modules/glue"
  job_name          = local.glue_job_name
  role_arn          = module.iam.glue_role_arn
  s3_bucket_id      = module.s3.bucket_id
  script_local_path = var.glue_script_path
  worker_type       = var.glue_worker_type
  num_workers       = var.glue_num_workers
}
