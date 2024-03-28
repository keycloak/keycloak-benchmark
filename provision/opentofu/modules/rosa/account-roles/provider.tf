terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.38.0"
    }
    rhcs = {
      source = "terraform-redhat/rhcs"
      version = "1.6.0-prerelease.1"
    }
  }
}

provider "rhcs" {
  token = var.rhcs_token
  url = "https://api.stage.openshift.com"
}
