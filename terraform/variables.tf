# Input variables for the Bliss platform IaC.
#
# Conventions:
# - `cloudflare_account_id` is the Cloudflare account UUID. Not secret;
#   safe to log and to commit in default tfvars (we do not commit any
#   tfvars — see `terraform/.gitignore`). Required because the same config
#   should target any account without code changes.
# - `pages_project_name` and `production_branch` are defaulted so a fresh
#   apply works without flags; override only when forking the project.

variable "cloudflare_account_id" {
  description = "Cloudflare account UUID that owns the Pages project. Not a secret; supplied via -var or TF_VAR_cloudflare_account_id."
  type        = string
  sensitive   = false

  validation {
    condition     = length(var.cloudflare_account_id) > 0
    error_message = "cloudflare_account_id must not be empty."
  }
}

variable "pages_project_name" {
  description = "Cloudflare Pages project name. Becomes the *.pages.dev subdomain. Lowercase, alphanumeric and hyphens only."
  type        = string
  default     = "bliss"

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{0,57}[a-z0-9]$", var.pages_project_name))
    error_message = "pages_project_name must be 2-58 chars, lowercase alphanumeric or hyphen, and start/end with alphanumeric."
  }
}

variable "production_branch" {
  description = "Git branch that maps to the production deployment. Per ADR-0001 §6, this is `main`."
  type        = string
  default     = "main"
}

variable "custom_domain" {
  description = "Apex domain attached to the Pages project (e.g. `wordsparrow.io`). Empty disables custom-domain attachment so a fresh bootstrap apply works without a domain registered."
  type        = string
  default     = "wordsparrow.io"

  validation {
    condition     = var.custom_domain == "" || can(regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$", var.custom_domain))
    error_message = "custom_domain must be empty or a valid lowercase apex (no scheme, no path, no leading `www.`)."
  }
}

variable "include_www_alias" {
  description = "Also attach `www.<custom_domain>` to the Pages project. Cloudflare Pages serves both; pair with a `_redirects` rule to canonicalize on the apex."
  type        = bool
  default     = true
}
