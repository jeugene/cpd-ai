output "job_name" {
  value = aws_glue_job.credit_cards_etl.name
}

output "database_name" {
  value = aws_glue_catalog_database.cpd.name
}

output "role_arn" {
  value = aws_iam_role.glue.arn
}
