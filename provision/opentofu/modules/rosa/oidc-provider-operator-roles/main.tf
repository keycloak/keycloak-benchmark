data "aws_caller_identity" "current" {}

###########################################################################
## Below is taken from rhcs-hcp module `oidc-config-and-provider`
###########################################################################

locals {
  path    = coalesce(var.path, "/")
  managed = var.oidc_config == "managed"
}

resource "rhcs_rosa_oidc_config" "oidc_config" {
  managed            = local.managed
  secret_arn         = local.managed ? null : module.aws_secrets_manager[0].secret_arn
  issuer_url         = local.managed ? null : rhcs_rosa_oidc_config_input.oidc_input[0].issuer_url
}

resource "aws_iam_openid_connect_provider" "oidc_provider" {
  url = "https://${rhcs_rosa_oidc_config.oidc_config.oidc_endpoint_url}"

  client_id_list = [
    "openshift",
    "sts.amazonaws.com"
  ]

  tags = var.tags

  thumbprint_list = [rhcs_rosa_oidc_config.oidc_config.thumbprint]
}

module "aws_s3_bucket" {
  source  = "terraform-aws-modules/s3-bucket/aws"
  version = "~> 5.2"

  count = local.managed ? 0 : 1

  bucket = rhcs_rosa_oidc_config_input.oidc_input[count.index].bucket_name
  tags = merge(var.tags, {
    red-hat-managed = true
  })

  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = false
  restrict_public_buckets = false

  attach_policy = true
  policy        = data.aws_iam_policy_document.allow_access_from_another_account[count.index].json
}

data "aws_iam_policy_document" "allow_access_from_another_account" {
  count = local.managed ? 0 : 1

  statement {
    principals {
      identifiers = ["*"]
      type        = "*"
    }
    sid    = "AllowReadPublicAccess"
    effect = "Allow"
    actions = [
      "s3:GetObject",
    ]

    resources = [
      format("arn:aws:s3:::%s/*", rhcs_rosa_oidc_config_input.oidc_input[count.index].bucket_name),
    ]
  }
}

resource "rhcs_rosa_oidc_config_input" "oidc_input" {
  count = local.managed ? 0 : 1

  region = data.aws_region.current.name
}

module "aws_secrets_manager" {
  source  = "terraform-aws-modules/secrets-manager/aws"
  version = ">=1.1.1"

  count = local.managed ? 0 : 1

  create      = true
  name        = rhcs_rosa_oidc_config_input.oidc_input[count.index].private_key_secret_name
  description = format("Secret for %s", rhcs_rosa_oidc_config_input.oidc_input[count.index].private_key_secret_name)

  tags = merge(var.tags, {
    red-hat-managed = true
  })

  secret_string = rhcs_rosa_oidc_config_input.oidc_input[count.index].private_key
}

resource "aws_s3_object" "discrover_doc_object" {
  count = local.managed ? 0 : 1

  bucket       = module.aws_s3_bucket[count.index].s3_bucket_id
  key          = ".well-known/openid-configuration"
  content      = rhcs_rosa_oidc_config_input.oidc_input[count.index].discovery_doc
  content_type = "application/json"

  tags = merge(var.tags, {
    red-hat-managed = true
  })
}

resource "aws_s3_object" "s3_object" {
  count = local.managed ? 0 : 1

  bucket       = module.aws_s3_bucket[count.index].s3_bucket_id
  key          = "keys.json"
  content      = rhcs_rosa_oidc_config_input.oidc_input[count.index].jwks
  content_type = "application/json"

  tags = merge(var.tags, {
    red-hat-managed = true
  })
}

data "aws_region" "current" {}

resource "time_sleep" "wait_10_seconds" {
  create_duration  = "10s"
  destroy_duration = "10s"
  triggers = {
    oidc_config_id       = rhcs_rosa_oidc_config.oidc_config.id
    oidc_endpoint_url    = rhcs_rosa_oidc_config.oidc_config.oidc_endpoint_url
    oidc_provider_url    = aws_iam_openid_connect_provider.oidc_provider.url
    discrover_doc_object = local.managed ? null : aws_s3_object.discrover_doc_object[0].checksum_sha1
    s3_object            = local.managed ? null : aws_s3_object.s3_object[0].checksum_sha1
  }
}

###########################################################################
## Below is taken from rhcs-hcp module `operator-roles`
###########################################################################

locals {
  operator_roles_properties = [
    {
      operator_name      = "installer-cloud-credentials"
      operator_namespace = "openshift-image-registry"
      role_name          = "openshift-image-registry-installer-cloud-credentials"
      policy_details     = data.rhcs_hcp_policies.all_policies.operator_role_policies["openshift_hcp_image_registry_installer_cloud_credentials_policy"]
      service_accounts   = ["system:serviceaccount:openshift-image-registry:cluster-image-registry-operator", "system:serviceaccount:openshift-image-registry:registry"]
    },
    {
      operator_name      = "cloud-credentials"
      operator_namespace = "openshift-ingress-operator"
      role_name          = "openshift-ingress-operator-cloud-credentials"
      policy_details     = data.rhcs_hcp_policies.all_policies.operator_role_policies["openshift_hcp_ingress_operator_cloud_credentials_policy"]
      service_accounts   = ["system:serviceaccount:openshift-ingress-operator:ingress-operator"]
    },
    {
      operator_name      = "ebs-cloud-credentials"
      operator_namespace = "openshift-cluster-csi-drivers"
      role_name          = "openshift-cluster-csi-drivers-ebs-cloud-credentials"
      policy_details     = data.rhcs_hcp_policies.all_policies.operator_role_policies["openshift_hcp_cluster_csi_drivers_ebs_cloud_credentials_policy"]
      service_accounts   = ["system:serviceaccount:openshift-cluster-csi-drivers:aws-ebs-csi-driver-operator", "system:serviceaccount:openshift-cluster-csi-drivers:aws-ebs-csi-driver-controller-sa"]
    },
    {
      operator_name      = "cloud-credentials"
      operator_namespace = "openshift-cloud-network-config-controller"
      role_name          = "openshift-cloud-network-config-controller-cloud-credentials"
      policy_details     = data.rhcs_hcp_policies.all_policies.operator_role_policies["openshift_hcp_cloud_network_config_controller_cloud_credentials_policy"]
      service_accounts   = ["system:serviceaccount:openshift-cloud-network-config-controller:cloud-network-config-controller"]
    },
    {
      operator_name      = "kube-controller-manager"
      operator_namespace = "kube-system"
      role_name          = "kube-system-kube-controller-manager"
      policy_details     = data.rhcs_hcp_policies.all_policies.operator_role_policies["openshift_hcp_kube_controller_manager_credentials_policy"]
      service_accounts   = ["system:serviceaccount:kube-system:kube-controller-manager"]
    },
    {
      operator_name      = "capa-controller-manager"
      operator_namespace = "kube-system"
      role_name          = "kube-system-capa-controller-manager"
      policy_details     = data.rhcs_hcp_policies.all_policies.operator_role_policies["openshift_hcp_capa_controller_manager_credentials_policy"]
      service_accounts   = ["system:serviceaccount:kube-system:capa-controller-manager"]
    },
    {
      operator_name      = "control-plane-operator"
      operator_namespace = "kube-system"
      role_name          = "kube-system-control-plane-operator"
      policy_details     = data.rhcs_hcp_policies.all_policies.operator_role_policies["openshift_hcp_control_plane_operator_credentials_policy"]
      service_accounts   = ["system:serviceaccount:kube-system:control-plane-operator"]
    },
    {
      operator_name      = "kms-provider"
      operator_namespace = "kube-system"
      role_name          = "kube-system-kms-provider"
      policy_details     = data.rhcs_hcp_policies.all_policies.operator_role_policies["openshift_hcp_kms_provider_credentials_policy"]
      service_accounts   = ["system:serviceaccount:kube-system:kms-provider"]
    },
  ]
  operator_roles_count = length(local.operator_roles_properties)
}

data "aws_iam_policy_document" "custom_trust_policy" {
  count = local.operator_roles_count

  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:oidc-provider/${rhcs_rosa_oidc_config.oidc_config.oidc_endpoint_url}"]
    }
    condition {
      test     = "StringEquals"
      variable = "${rhcs_rosa_oidc_config.oidc_config.oidc_endpoint_url}:sub"
      values   = local.operator_roles_properties[count.index].service_accounts
    }
  }
}

module "operator_iam_role" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-assumable-role"
  version = ">=5.34.0"
  count   = local.operator_roles_count

  create_role = true

  role_name = substr("${var.operator_role_prefix}-${local.operator_roles_properties[count.index].operator_namespace}-${local.operator_roles_properties[count.index].operator_name}", 0, 64)

  role_path                     = var.path
  role_permissions_boundary_arn = ""

  create_custom_role_trust_policy = true
  custom_role_trust_policy        = data.aws_iam_policy_document.custom_trust_policy[count.index].json

  custom_role_policy_arns = [
    "${local.operator_roles_properties[count.index].policy_details}"
  ]

  tags = merge(var.tags, {
    rosa_managed_policies = true
    rosa_hcp_policies     = true
    red-hat-managed       = true
    operator_namespace    = "${local.operator_roles_properties[count.index].operator_namespace}"
    operator_name         = "${local.operator_roles_properties[count.index].operator_name}"
  })
}

data "rhcs_hcp_policies" "all_policies" {}
data "rhcs_info" "current" {}

resource "time_sleep" "role_resources_propagation" {
  create_duration = "20s"
  triggers = {
    operator_role_prefix = var.operator_role_prefix
    operator_role_arns   = jsonencode([for value in module.operator_iam_role : value.iam_role_arn])
  }
}
