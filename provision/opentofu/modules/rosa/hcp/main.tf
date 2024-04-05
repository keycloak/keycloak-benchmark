data "aws_availability_zones" "available" {
  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

locals {
  azs                  = slice(data.aws_availability_zones.available.names, 0, 1)
  account_role_prefix  = var.cluster_name
  operator_role_prefix = var.cluster_name
  sts_roles            = {
    role_arn           = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${local.account_role_prefix}-HCP-ROSA-Installer-Role",
    support_role_arn   = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${local.account_role_prefix}-HCP-ROSA-Support-Role",
    instance_iam_roles = {
      worker_role_arn = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${local.account_role_prefix}-HCP-ROSA-Worker-Role"
    }
    operator_role_prefix = local.operator_role_prefix,
    oidc_config_id       = module.operator-roles.oidc_config_id
  }
}

module "account-roles" {
  source = "../account-roles"

  account_role_prefix = local.account_role_prefix
  rhcs_token          = var.rhcs_token
}

module "operator-roles" {
  source = "../oidc-provider-operator-roles"

  oidc_config          = "managed"
  operator_role_prefix = local.operator_role_prefix
}

module "vpc" {
  source     = "../vpc"
  # This module doesn't really depend on these modules, but ensuring these are executed first lets us fail-fast if there
  # are issues with the roles and prevents us having to wait for a VPC to be provisioned before errors are reported
  depends_on = [module.account-roles, module.operator-roles]

  cluster_name       = var.cluster_name
  region             = var.region
  subnet_azs         = local.azs
  subnet_cidr_prefix = var.subnet_cidr_prefix
  vpc_cidr           = var.vpc_cidr
}

data "aws_caller_identity" "current" {
}

resource "rhcs_cluster_rosa_hcp" "rosa_hcp_cluster" {
  availability_zones     = local.azs
  aws_account_id         = data.aws_caller_identity.current.account_id
  aws_billing_account_id = data.aws_caller_identity.current.account_id
  aws_subnet_ids         = module.vpc.cluster-subnets
  cloud_region           = var.region
  machine_cidr           = var.vpc_cidr
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
