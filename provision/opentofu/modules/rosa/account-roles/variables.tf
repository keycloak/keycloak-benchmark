variable "rhcs_token" {
  type      = string
  sensitive = true
}

variable "account_role_prefix" {
  type    = string
  default = null
}

variable "path" {
  description = "(Optional) The arn path for the account/operator roles as well as their policies."
  type        = string
  default     = "/"
}

variable "tags" {
  description = "List of AWS resource tags to apply."
  type        = map(string)
  default     = null
}
