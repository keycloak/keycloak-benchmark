variable "operator_role_prefix" {
  type = string
}

variable "oidc_config" {
  type    = string
  default = ""
}

variable "path" {
  description = "(Optional) The arn path for the account/operator roles as well as their policies."
  type        = string
  default     = null
}

variable "tags" {
  type        = map(string)
  default     = null
  description = "List of AWS resource tags to apply."
}
