# ── Lambda ─────────────────────────────────────────────────────────────────────

data "aws_iam_policy_document" "lambda_assume_role" {
  statement {
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
    actions = ["sts:AssumeRole"]
  }
}

data "aws_iam_policy_document" "lambda" {
  statement {
    sid     = "CloudWatchLogs"
    actions = ["logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents"]
    resources = [
      "arn:aws:logs:${var.aws_region}:${var.account_id}:log-group:/aws/lambda/${var.lambda_function_name}",
      "arn:aws:logs:${var.aws_region}:${var.account_id}:log-group:/aws/lambda/${var.lambda_function_name}:*",
    ]
  }

  statement {
    sid     = "S3Read"
    actions = ["s3:GetObject", "s3:ListBucket"]
    resources = [
      var.s3_bucket_arn,
      "${var.s3_bucket_arn}/*",
    ]
  }

  statement {
    sid = "SQSConsume"
    actions = [
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:GetQueueAttributes",
      "sqs:ChangeMessageVisibility",
    ]
    resources = [var.sqs_arn]
  }

  statement {
    sid = "GlueCatalog"
    actions = [
      "glue:GetTable",
      "glue:GetDatabase",
      "glue:GetDatabases",
      "glue:GetPartitions",
      "glue:GetPartition",
      "glue:BatchCreatePartition",
      "glue:BatchDeletePartition",
      "glue:UpdateTable",
      "glue:CreateTable",
    ]
    resources = [
      "arn:aws:glue:${var.aws_region}:${var.account_id}:catalog",
      "arn:aws:glue:${var.aws_region}:${var.account_id}:database/${var.app_code}_*",
      "arn:aws:glue:${var.aws_region}:${var.account_id}:table/${var.app_code}_*/*",
    ]
  }
}

resource "aws_iam_role" "lambda" {
  name               = "${var.lambda_function_name}-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

resource "aws_iam_role_policy" "lambda" {
  name   = "${var.lambda_function_name}-policy"
  role   = aws_iam_role.lambda.id
  policy = data.aws_iam_policy_document.lambda.json
}

# ── Glue ───────────────────────────────────────────────────────────────────────

data "aws_iam_policy_document" "glue_assume_role" {
  statement {
    principals {
      type        = "Service"
      identifiers = ["glue.amazonaws.com"]
    }
    actions = ["sts:AssumeRole"]
  }
}

data "aws_iam_policy_document" "glue_s3" {
  statement {
    sid = "S3DataAccess"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:ListBucket",
    ]
    resources = [
      var.s3_bucket_arn,
      "${var.s3_bucket_arn}/*",
    ]
  }
}

resource "aws_iam_role" "glue" {
  name               = "${var.glue_job_name}-role"
  assume_role_policy = data.aws_iam_policy_document.glue_assume_role.json
}

resource "aws_iam_role_policy" "glue_s3" {
  name   = "${var.glue_job_name}-s3-policy"
  role   = aws_iam_role.glue.id
  policy = data.aws_iam_policy_document.glue_s3.json
}

resource "aws_iam_role_policy_attachment" "glue_service" {
  role       = aws_iam_role.glue.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSGlueServiceRole"
}
