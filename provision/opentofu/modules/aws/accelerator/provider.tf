terraform {
  backend "s3" {
    bucket         = "kcb-tf-state"
    key            = "accelerator"
    region         = "eu-west-1"
    encrypt        = true
    dynamodb_table = "app-state"
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.38.0"
    }
  }

  required_version = ">= 1.4.0"
}

provider "aws" {
  # us-west-2 must be used for all global accelerator commands
  region     = "us-west-2"
  # Force set sts_region to preventing hanging on invalid regions
  sts_region = "us-east-1"
}

provider "aws" {
  region = var.aws_region
  alias = "clusters"
}
