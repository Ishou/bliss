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

# ─── Fly.io API tier (ADR-0007) ────────────────────────────────────────────

variable "cloudflare_zone_id" {
  description = "Cloudflare Zone ID for the apex domain (`var.custom_domain`). Required when `var.custom_domain` is non-empty so the API CNAME (`api.<custom_domain>` → Fly) can be created. Not a secret; visible on the Cloudflare dashboard zone overview. Empty disables DNS-record management so a bootstrap apply works before the zone exists."
  type        = string
  default     = ""

  validation {
    condition     = var.cloudflare_zone_id == "" || can(regex("^[a-f0-9]{32}$", var.cloudflare_zone_id))
    error_message = "cloudflare_zone_id must be empty or a 32-character hex zone UUID."
  }
}

variable "fly_org" {
  description = "Fly.io organisation that owns the API app. Defaults to `personal` (the org every Fly account auto-provisions). Override when the project moves to a paid org."
  type        = string
  default     = "personal"
}

variable "fly_app_name" {
  description = "Fly.io app name for the JVM API. Becomes the *.fly.dev hostname (e.g. wordsparrow-api.fly.dev). Lowercase alphanumeric and hyphens, 1-30 chars per Fly's app-naming rules."
  type        = string
  default     = "wordsparrow-api"

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{0,28}[a-z0-9]$", var.fly_app_name))
    error_message = "fly_app_name must be 2-30 chars, lowercase alphanumeric or hyphen, and start/end with alphanumeric."
  }
}

variable "fly_region" {
  description = "Fly.io region for the API machine and Postgres cluster. Default `cdg` (Paris) per ADR-0007 §2 — French audience, low front↔back round-trip via Cloudflare's Paris POPs. Other plausible regions: `ams` (Amsterdam), `lhr` (London), `fra` (Frankfurt). See `https://fly.io/docs/reference/regions/` for the live list."
  type        = string
  default     = "cdg"

  validation {
    condition     = can(regex("^[a-z]{3}$", var.fly_region))
    error_message = "fly_region must be a 3-letter Fly region code (e.g. cdg, ams, fra)."
  }
}

variable "fly_machine_cpus" {
  description = "vCPU count for the API machine. ADR-0007 §2 sizes v1 at 1× shared-CPU."
  type        = number
  default     = 1

  validation {
    condition     = var.fly_machine_cpus >= 1 && var.fly_machine_cpus <= 16
    error_message = "fly_machine_cpus must be between 1 and 16."
  }
}

variable "fly_machine_memory_mb" {
  description = "Memory in MB for the API machine. ADR-0007 §2 sizes v1 at 256-512 MB; default 512 leaves headroom for JVM startup spikes without crossing the next billing tier."
  type        = number
  default     = 512

  validation {
    condition     = contains([256, 512, 1024, 2048, 4096, 8192], var.fly_machine_memory_mb)
    error_message = "fly_machine_memory_mb must be one of: 256, 512, 1024, 2048, 4096, 8192."
  }
}

variable "fly_machine_image" {
  description = "Docker image reference the Fly machine boots. Empty (default) = the machine resource is *not* pre-seeded with an image; `flyctl deploy` from CI builds and pushes the image, then attaches it. This matches the ADR-0007 §3 'GitHub Actions → flyctl deploy' model. Set non-empty only for debugging or to pin a specific tag from outside CI."
  type        = string
  default     = ""
}

variable "fly_postgres_app_name" {
  description = "Fly.io app name for the Postgres cluster. Created by the maintainer via `flyctl postgres create` (the andrewbaxter/fly provider has no postgres resource — see `fly-postgres.tf` for the deviation note). Must follow the same 2-30 char lowercase-alphanumeric-hyphen rule as `fly_app_name`."
  type        = string
  default     = "wordsparrow-api-db"

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{0,28}[a-z0-9]$", var.fly_postgres_app_name))
    error_message = "fly_postgres_app_name must be 2-30 chars, lowercase alphanumeric or hyphen, and start/end with alphanumeric."
  }
}

variable "fly_postgres_volume_size_gb" {
  description = "Volume size in GB for the Postgres primary. ADR-0007 §2 sizes v1 at smallest tier (1 GB). Documented here for the maintainer's `flyctl postgres create --volume-size` flag; not consumed by any TF resource today (see `fly-postgres.tf` deviation note)."
  type        = number
  default     = 1

  validation {
    condition     = var.fly_postgres_volume_size_gb >= 1 && var.fly_postgres_volume_size_gb <= 500
    error_message = "fly_postgres_volume_size_gb must be between 1 and 500."
  }
}
