variable "rhcs_token" {
  type      = string
  sensitive = true
}

variable "region" {
  description = <<-EOT
    Region to create AWS infrastructure resources for a
      ROSA with hosted control planes cluster.
  EOT
  type        = string
}

variable "availability_zones" {
  description = "CSV of Availability Zones to create cluster in."
  type        = string
}

variable "cluster_name" {
  description = "Name of the ROSA hosted control planes cluster to be created."
  type        = string
  default     = "rosa-hcp"

  validation {
    condition     = can(regex("^[a-z][-a-z0-9]{0,13}[a-z0-9]$", var.cluster_name))
    error_message = <<-EOT
      ROSA cluster names must be less than 16 characters.
        May only contain lower case, alphanumeric, or hyphens characters.
    EOT
  }
}

variable "vpc_cidr" {
  description = <<-EOT
    IPv4 CIDR netmask for the VPC resource.
      This should equal or include the cluster's Machine CIDR netmask.
  EOT
  type        = string
  default     = "10.0.0.0/16"

  validation {
    condition     = can(cidrnetmask(var.vpc_cidr))
    error_message = "VPC CIDR must be a valid CIDR netmask, e.g., '10.0.0.0/16'."
  }
}

variable "subnet_cidr_prefix" {
  description = <<-EOT
    The CIDR prefix value to use when dividing up the VPC CIDR range into subnet ranges.
      E.g., 24 to create equal subnets of size "24": 10.0.1.0/24, 10.0.2.0/24, etc.
  EOT
  type        = number
  default     = 24
}

variable "openshift_version" {
  type    = string
  default = "4.15.11"
  nullable = false
}

variable "instance_type" {
  type     = string
  default  = "m5.4xlarge"
  nullable = false
}

variable "replicas" {
  type     = number
  default  = 2
  nullable = false
}
