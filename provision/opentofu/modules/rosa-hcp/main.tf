module "vpc" {
  source                = "terraform-redhat/rosa-hcp/rhcs//modules/vpc"

  name_prefix           = var.cluster_name
  availability_zones    = split(",", var.availability_zones)

  vpc_cidr              = var.vpc_cidr
}

module "hcp" {
  source = "terraform-redhat/rosa-hcp/rhcs"

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
  admin_credentials_password = var.cluster_admin_password
}

