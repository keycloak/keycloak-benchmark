output "cluster-private-subnets" {
  value       = module.vpc.private_subnets
  description = "List of private subnet IDs created."
}

output "cluster-public-subnets" {
  value       = module.vpc.public_subnets
  description = "List of public subnet IDs created."
}

output "cluster-subnets" {
  value       = concat(module.vpc.public_subnets, module.vpc.private_subnets)
  description = "List of both public and private subnets."
}
