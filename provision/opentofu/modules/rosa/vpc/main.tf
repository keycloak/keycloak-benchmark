locals {
  supported_regions = {
    "ap-northeast-1" = "apne1"
    "ap-southeast-1" = "apse1"
    "ap-southeast-2" = "apse2"
    "ap-southeast-3" = "apse3"
    "ap-southeast-4" = "apse4"
    "ca-central-1"   = "cac1"
    "eu-central-1"   = "euc1"
    "eu-west-1"      = "euw1"
    "us-east-1"      = "use1"
    "us-east-2"      = "use2"
    "us-west-2"      = "usw2"
    "ap-south-2"     = "aps2"
  }
  well_known_az_ids = {
    us-east-1 = [2, 4, 6]
    ap-northeast-1 = [1, 2, 4]
  }
  az_id_prefix = lookup(local.supported_regions, var.region, null) != null ? "${local.supported_regions[var.region]}-az" : "unknown-az"
  azs = (
    length(var.subnet_azs) > 0 ?
    (var.single_az_only ? [var.subnet_azs[0]] : var.subnet_azs) :
    (var.single_az_only ?
      ["${local.az_id_prefix}${lookup(local.well_known_az_ids, var.region, [1, 2, 3])[0]}"] :
      [for id in lookup(local.well_known_az_ids, var.region, [1, 2, 3]) : "${local.az_id_prefix}${id}"]
    )
  )
  # If an AZ has a single hyphen, it's an AZ ID
  az_ids = [for az in local.azs : az if length(split("-", az)) == 2]
  # If an AZ has a two hyphens, it's an AZ name
  az_names = [for az in local.azs : az if length(split("-", az)) == 3]

  vpc_cidr_prefix = tonumber(split("/", var.vpc_cidr)[1])
  subnet_newbits  = var.subnet_cidr_prefix - local.vpc_cidr_prefix
  subnet_count    = var.private_subnets_only ? length(local.azs) : length(local.azs) * 2
  all_subnets = (
    var.private_subnets_only ?
    [
      local.subnet_newbits == 0 ?
      [var.vpc_cidr] :
      cidrsubnets(var.vpc_cidr, [for i in range(length(local.azs)) : local.subnet_newbits]...), []
    ] :
    [
      for cidr_block in cidrsubnets(var.vpc_cidr, 1, 1) :
      local.subnet_newbits == 1 ?
      [cidr_block] :
      cidrsubnets(cidr_block, [for i in range(length(local.azs)) : local.subnet_newbits - 1]...)
    ]
  )
}


# Performing multi-input validations in null_resource block
# https://github.com/hashicorp/terraform/issues/25609
resource "null_resource" "validations" {
  lifecycle {
    precondition {
      condition     = lookup(local.supported_regions, var.region, null) != null
      error_message = <<-EOT
        ROSA with hosted control planes is currently only available in these regions:
          ${join(", ", keys(local.supported_regions))}.
      EOT
    }

    precondition {
      condition     = local.vpc_cidr_prefix <= var.subnet_cidr_prefix
      error_message = "Subnet CIDR prefix must be smaller prefix (larger number) than the VPC CIDR prefix."
    }

    precondition {
      condition     = !(var.single_az_only && length(var.subnet_azs) > 1)
      error_message = <<-EOT
        It's invalid to supply more than 1 `subnet_azs` while also specifying `single_az_only=true` (default).
          To use more than 1 availability zone, set `-var single_az_only=false`.
          Or set `-var 'subnet_azs=["${length(var.subnet_azs) > 0 ? var.subnet_azs[0] : "none"}"]'`
      EOT
    }

    precondition {
      condition     = (length(local.az_ids) > 0 && length(local.az_names) == 0) || (length(local.az_ids) == 0 && length(local.az_names) > 0)
      error_message = <<-EOT
        Make sure to provide subnet_azs in either name format OR zone ID, do not mix and match.
          E.g., us-east-1a,us-east-1b OR use1-az1,use1-az2
      EOT
    }

    precondition {
      condition     = local.subnet_count <= pow(2, local.subnet_newbits)
      error_message = <<-EOT
        The size of available IP space is not enough to accomodate the expected number of subnets:
          Try increasing the size of your VPC CIDR, e.g., 10.0.0.0/16 -> 10.0.0.0/14
          Or try decreasing the size of your Subnet Prefix, e.g., 24 -> 28
      EOT
    }

    precondition {
      condition     = alltrue([for name in local.az_names : contains(data.aws_availability_zones.available_azs.names, name)])
      error_message = <<-EOT
        ROSA with hosted control planes in region ${var.region} does not currently support availability zone name(s):
          ${join(", ", [for name in local.az_names : name if !contains(data.aws_availability_zones.available_azs.names, name)])}
      EOT
    }

    precondition {
      condition     = alltrue([for id in local.az_ids : contains(data.aws_availability_zones.available_azs.zone_ids, id)])
      error_message = <<-EOT
        ROSA with hosted control planes in region ${var.region} does not currently support availability zone ID(s):
          ${join(", ", [for id in local.az_ids : id if !contains(data.aws_availability_zones.available_azs.zone_ids, id)])}
       EOT
    }
  }
}


data "aws_availability_zones" "available_azs" {
  state = "available"
  filter {
    name = "opt-in-status"
    # Currently, no support for Local Zones, Wavelength, or Outpost
    values = ["opt-in-not-required"]
  }
}


module "vpc" {
  depends_on = [resource.null_resource.validations]

  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.0.0"

  name = "${var.cluster_name}-vpc"
  cidr = var.vpc_cidr

  azs             = local.azs
  private_subnets = local.all_subnets[0]
  public_subnets  = local.all_subnets[1]
  # Tags defined per https://repost.aws/knowledge-center/eks-vpc-subnet-discovery
  private_subnet_tags = merge(var.extra_tags ,
    {
      "kubernetes.io/role/internal-elb" = "1"
    })
  public_subnet_tags = merge(var.extra_tags ,
    {
      "kubernetes.io/role/elb" = "1"
    })

  enable_nat_gateway            = true
  enable_dns_hostnames          = true
  enable_dns_support            = true
  manage_default_security_group = false

  tags = merge(var.extra_tags,
    {
      Terraform    = "true"
      service      = "ROSA"
      cluster_name = var.cluster_name
    })
}
