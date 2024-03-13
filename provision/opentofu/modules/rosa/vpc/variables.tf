variable "region" {
  description = <<-EOT
    Region to create AWS infrastructure resources for a
      ROSA with hosted control planes cluster. (required)
  EOT
  type        = string
}

variable "subnet_azs" {
  # Usage: -var 'subnet_azs=["us-east-1a"]' or -var 'subnet_azs["use1-az1"]'
  description = <<-EOT
    List of availability zone names or IDs that subnets can get deployed into.
      If not provided, defaults to well known AZ IDs for each region.
      Does not currently support Local Zones, Outpost, or Wavelength.
  EOT
  type        = list(string)
  default     = []
}

variable "cluster_name" {
  description = "Name of the created ROSA with hosted control planes cluster."
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

variable "private_subnets_only" {
  description = "Only create private subnets"
  type        = bool
  default     = false
}

variable "single_az_only" {
  description = "Only create subnets in a single availability zone"
  type        = bool
  default     = true
}

variable "extra_tags" {
  description = "Extra tags to apply to AWS resources"
  type = map
  default = {}
}