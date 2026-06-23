provider "aws" {
  default_tags {
    tags = {
      AppCode     = var.app_code
      Environment = var.env
      ManagedBy   = "terraform"
      Project     = "cpd-ai"
    }
  }
}
