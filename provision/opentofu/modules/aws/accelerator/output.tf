output "dns_name" {
  value = module.global_accelerator.dns_name
}

output "webhook_url" {
  value = module.lambda_function.lambda_function_url
}

output "input_lb_service_name" {
  value = var.lb_service_name
}

output "input_name" {
  value = var.name
}

output "input_aws_region" {
  value = var.aws_region
}

output "input_site_a" {
  value = var.site_a
}

output "input_site_b" {
  value = var.site_b
}
