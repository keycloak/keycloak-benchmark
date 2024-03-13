data "aws_availability_zones" "available" {
  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

data "aws_availability_zones" "available_azs" {
  state = "available"
  filter {
    name   = "opt-in-status"
    # Currently, no support for Local Zones, Wavelength, or Outpost
    values = ["opt-in-not-required"]
  }
}

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
    us-east-1      = [2, 4, 6]
    ap-northeast-1 = [1, 2, 4]
  }
  azs      = slice(data.aws_availability_zones.available.names, 0, 1)
  # If an AZ has a single hyphen, it's an AZ ID
  az_ids   = [for az in local.azs : az if length(split("-", az)) == 2]
  # If an AZ has a two hyphens, it's an AZ name
  az_names = [for az in local.azs : az if length(split("-", az)) == 3]

  vpc_cidr_prefix = tonumber(split("/", var.vpc_cidr)[1])
  subnet_newbits  = var.subnet_cidr_prefix - local.vpc_cidr_prefix
  subnet_count    = var.private_subnets_only ? length(local.azs) : length(local.azs) * 2

  account_role_prefix  = var.cluster_name
  operator_role_prefix = var.cluster_name
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
      condition = alltrue([
        for name in local.az_names :contains(data.aws_availability_zones.available_azs.names, name)
      ])
      error_message = <<-EOT
        ROSA with hosted control planes in region ${var.region} does not currently support availability zone name(s):
          ${join(", ", [for name in local.az_names : name if !contains(data.aws_availability_zones.available_azs.names, name)])}
      EOT
    }

    precondition {
      condition = alltrue([
        for id in local.az_ids :contains(data.aws_availability_zones.available_azs.zone_ids, id)
      ])
      error_message = <<-EOT
        ROSA with hosted control planes in region ${var.region} does not currently support availability zone ID(s):
          ${join(", ", [for id in local.az_ids : id if !contains(data.aws_availability_zones.available_azs.zone_ids, id)])}
       EOT
    }
  }
}

module "account-roles" {
  source = "../account-roles"

  account_role_prefix = local.account_role_prefix
  path                = var.path
  rhcs_token               = var.rhcs_token
}

module "operator-roles" {
  source = "../oidc-provider-operator-roles"

  oidc_config          = "managed"
  operator_role_prefix = local.operator_role_prefix
  path                 = var.path
}

data "external" "rosa" {
  program = [
    "bash", "${path.module}/scripts/rosa_machine_cidr.sh"
  ]
  query = {
    cluster_name = var.cluster_name
  }
}

module "vpc" {
  source     = "../vpc"
  # This module doesn't really depend on these modules, but ensuring these are executed first lets us fail-fast if there
  # are issues with the roles and prevents us having to wait for a VPC to be provisioned before errors are reported
  depends_on = [module.account-roles, module.operator-roles]

  cluster_name       = var.cluster_name
  region             = var.region
  subnet_azs         = local.azs
  subnet_cidr_prefix = 28
  vpc_cidr           = data.external.rosa.result.cidr
}

data "aws_caller_identity" "current" {
}

locals {
  account_role_path = coalesce(var.path, "/")
  sts_roles         = {
    role_arn           = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role${local.account_role_path}${local.account_role_prefix}-HCP-ROSA-Installer-Role",
    support_role_arn   = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role${local.account_role_path}${local.account_role_prefix}-HCP-ROSA-Support-Role",
    instance_iam_roles = {
      worker_role_arn = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role${local.account_role_path}${local.account_role_prefix}-HCP-ROSA-Worker-Role"
    }
    operator_role_prefix = local.operator_role_prefix,
    oidc_config_id       = module.operator-roles.oidc_config_id
  }
}

resource "rhcs_cluster_rosa_hcp" "rosa_hcp_cluster" {
  availability_zones     = local.azs
  aws_account_id         = data.aws_caller_identity.current.account_id
  aws_billing_account_id = data.aws_caller_identity.current.account_id
  aws_subnet_ids         = module.vpc.cluster-subnets
  cloud_region           = var.region
  machine_cidr           = data.external.rosa.result.cidr
  name                   = var.cluster_name
  properties             = merge(
    {
      rosa_creator_arn = data.aws_caller_identity.current.arn
    },
  )
  sts                      = local.sts_roles
  replicas                 = var.replicas
  version                  = var.openshift_version
  wait_for_create_complete = true
}

# TODO
#resource "rhcs_hcp_machine_pool" "my_pool" {
#  cluster       = rhcs_cluster_rosa_hcp.rosa_hcp_cluster.name
#  name          = "scaling"
#  aws_node_pool = {
#    instance_type = var.instance_type
#  }
#  autoscaling = {
#    enabled      = true,
#    max_replicas = 10,
#    min_replicas = 0
#  }
#  subnet_id = module.vpc.cluster-public-subnets[0]
#}

resource "rhcs_cluster_wait" "rosa_cluster" {
  cluster = rhcs_cluster_rosa_hcp.rosa_hcp_cluster.id
  timeout = 30
}

resource "null_resource" "create_admin" {
  depends_on = [rhcs_cluster_wait.rosa_cluster]
  provisioner "local-exec" {
    command     = "./scripts/rosa_recreate_admin.sh"
    environment = {
      CLUSTER_NAME = var.cluster_name
    }
    interpreter = ["bash"]
    working_dir = path.module
  }
}

resource "null_resource" "rosa_verify_network" {
  depends_on = [null_resource.create_admin]
  provisioner "local-exec" {
    command     = "./scripts/rosa_verify_network.sh"
    environment = {
      CLUSTER_NAME = var.cluster_name
    }
    interpreter = ["bash"]
    working_dir = path.module
  }
}
