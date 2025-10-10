terraform {
  backend "s3" {
    bucket         = "kcb-tf-state"
    key            = "vpc"
    region         = "eu-west-1"
    encrypt        = true
    dynamodb_table = "app-state"
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.38.0"
    }
    null = {
      source  = "hashicorp/null"
      version = "~> 3.2"
    }
    rhcs = {
      source  = "terraform-redhat/rhcs"
      version = "1.7.1"
    }
  }

  required_version = ">= 1.4.0"
}

provider "aws" {
  region     = var.region
  # Force set sts_region to preventing hanging on invalid regions
  sts_region = "us-east-1"
}

provider "rhcs" {
  token = var.rhcs_token
  url   = "https://api.openshift.com"
}
