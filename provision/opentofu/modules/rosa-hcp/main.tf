data "aws_caller_identity" "current" {
}

data "aws_secretsmanager_secret" "secrets" {
  region = "eu-central-1"
  name = "keycloak-master-password"
}

data "aws_secretsmanager_secret_version" "current" {
  secret_id = data.aws_secretsmanager_secret.secrets.id
}

module "vpc" {
  source                = "terraform-redhat/rosa-hcp/rhcs//modules/vpc"

  name_prefix           = var.cluster_name
  availability_zones    = split(",", var.availability_zones)

  vpc_cidr              = var.vpc_cidr
}

module "hcp" {
  source = "terraform-redhat/rosa-hcp/rhcs"
  version = "1.7.0"

  openshift_version      = var.openshift_version
  cluster_name           = var.cluster_name
  machine_cidr           = var.vpc_cidr
  aws_subnet_ids         = concat(module.vpc.public_subnets, module.vpc.private_subnets)
  aws_availability_zones = module.vpc.availability_zones
  replicas               = var.replicas
  compute_machine_type   = var.instance_type

  // STS configuration
  create_account_roles  = true
  account_role_prefix   = var.cluster_name
  create_oidc           = true
  create_operator_roles = true
  operator_role_prefix  = var.cluster_name

  // admin credentials
  admin_credentials_username = "cluster-admin"
  admin_credentials_password = nonsensitive(data.aws_secretsmanager_secret_version.current.secret_string)
}

