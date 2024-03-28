output "input_cluster_name" {
  value       = var.cluster_name
  description = "The name of the created ROSA hosted control planes cluster."
}

output "input_region" {
  value       = var.region
  description = "The region AWS resources created in."
}
