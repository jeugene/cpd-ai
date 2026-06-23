resource "aws_s3_object" "etl_script" {
  bucket = var.s3_bucket_id
  key    = "scripts/credit_cards.py"
  source = var.script_local_path
  etag   = filemd5(var.script_local_path)
}

resource "aws_glue_catalog_database" "cpd" {
  name        = "${replace(var.job_name, "-", "_")}_db"
  description = "Iceberg tables for the ${var.job_name} pipeline"
}

resource "aws_glue_job" "credit_cards_etl" {
  name         = var.job_name
  description  = "PySpark ETL: reads credit card JSON from S3, upserts into Iceberg via Glue catalog"
  role_arn     = var.role_arn
  glue_version = "4.0"

  command {
    name            = "glueetl"
    script_location = "s3://${var.s3_bucket_id}/scripts/credit_cards.py"
    python_version  = "3"
  }

  default_arguments = {
    "--job-language"                     = "python"
    "--enable-metrics"                   = "true"
    "--enable-continuous-cloudwatch-log" = "true"
    "--enable-spark-ui"                  = "true"
    "--spark-event-logs-path"            = "s3://${var.s3_bucket_id}/spark-logs/"
    "--TempDir"                          = "s3://${var.s3_bucket_id}/tmp/"
    "--enable-glue-datacatalog"          = "true"
    "--datalake-formats"                 = "iceberg"
    "--conf" = join(" ", [
      "spark.sql.catalog.glue_catalog=org.apache.iceberg.spark.SparkCatalog",
      "--conf spark.sql.catalog.glue_catalog.catalog-impl=org.apache.iceberg.aws.glue.GlueCatalog",
      "--conf spark.sql.catalog.glue_catalog.io-impl=org.apache.iceberg.aws.s3.S3FileIO",
      "--conf spark.sql.catalog.glue_catalog.warehouse=s3://${var.s3_bucket_id}/warehouse/",
    ])
  }

  worker_type       = var.worker_type
  number_of_workers = var.num_workers

  execution_property {
    max_concurrent_runs = 1
  }

  depends_on = [aws_s3_object.etl_script]
}
