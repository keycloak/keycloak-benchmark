data "aws_lb" "site_a" {
  provider = aws.clusters
  tags     = {
    "kubernetes.io/service-name" = var.lb_service_name
    "site"                       = var.site_a
  }
}

data "aws_lb" "site_b" {
  provider = aws.clusters
  tags     = {
    "kubernetes.io/service-name" = var.lb_service_name
    "site"                       = var.site_b
  }
}

# https://github.com/terraform-aws-modules/terraform-aws-global-accelerator
module "global_accelerator" {
  source = "terraform-aws-modules/global-accelerator/aws"

  name = var.name
  ip_address_type = "DUAL_STACK"

  listeners = {
    listener_1 = {
      endpoint_group = {
        endpoint_group_region   = var.aws_region
        traffic_dial_percentage = 100

        endpoint_configuration = [
          {
            client_ip_preservation_enabled = false
            endpoint_id                    = data.aws_lb.site_a.arn
            weight                         = 128
          }, {
            client_ip_preservation_enabled = false
            endpoint_id                    = data.aws_lb.site_b.arn
            weight                         = 128
          }
        ]
      }
      port_ranges = [
        {
          from_port = 443
          to_port   = 443
        }
      ]
      protocol = "TCP"
    }
  }

  tags = {
    accelerator = var.name
  }
}

# https://github.com/terraform-aws-modules/terraform-aws-lambda
module "lambda_function" {
  source = "terraform-aws-modules/lambda/aws"

  providers = {
    aws = aws.clusters
  }

  function_name              = var.name
  handler                    = "fencing_lambda.handler"
  runtime                    = "python3.12"
  source_path                = "src/fencing_lambda.py"
  create_lambda_function_url = true
  timeout                    = 15

  attach_policies = true
  policies        = [
    "arn:aws:iam::aws:policy/ElasticLoadBalancingReadOnly",
    "arn:aws:iam::aws:policy/GlobalAcceleratorFullAccess"
  ]
  number_of_policies = 2

  attach_policy_jsons = true
  policy_jsons = [
    <<-EOT
      {
          "Version": "2012-10-17",
          "Statement": [
              {
                  "Action": [
                      "secretsmanager:GetSecretValue"
                  ],
                  "Effect": "Allow",
                  "Resource": ["*"]
              }
          ]
      }
    EOT
  ]
  number_of_policy_jsons = 1

  tags = {
    accelerator = var.name
  }
}
