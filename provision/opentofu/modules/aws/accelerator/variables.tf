variable "lb_service_name" {
  type     = string
}

variable "name" {
  type     = string
  nullable = false
}

variable "aws_region" {
  type     = string
  nullable = false
}

variable "site_a" {
  type     = string
}

variable "site_b" {
  type     = string
}
