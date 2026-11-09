terraform {
  required_version = ">= 1.9"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }
  # 상태(state)는 처음엔 로컬. 협업/실전이면 S3+DynamoDB 백엔드로 전환 권장(주석 처리해 둠).
  # backend "s3" {
  #   bucket = "geuneul-tfstate"
  #   key    = "infra/terraform.tfstate"
  #   region = "ap-northeast-2"
  # }
}

provider "aws" {
  region = var.aws_region
  default_tags {
    tags = {
      Project   = var.project
      ManagedBy = "terraform"
    }
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_caller_identity" "current" {}
