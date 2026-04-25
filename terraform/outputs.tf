# Outputs surfaced after `terraform apply`.
#
# `pages_subdomain` is the canonical *.pages.dev hostname Cloudflare assigns
# to the project (e.g. `bliss.pages.dev`). The deploy workflow does not
# read it — Cloudflare routes by project name — but `docs/deploy.md` and
# the maintainer reference it after the bootstrap apply.

output "pages_project_name" {
  description = "Cloudflare Pages project name (matches the *.pages.dev subdomain root)."
  value       = cloudflare_pages_project.frontend.name
}

output "pages_subdomain" {
  description = "Production hostname assigned by Cloudflare Pages (e.g. bliss.pages.dev)."
  value       = cloudflare_pages_project.frontend.subdomain
}

output "custom_domain" {
  description = "Apex custom domain attached to the Pages project. Empty when `var.custom_domain` is empty."
  value       = var.custom_domain
}

output "production_url" {
  description = "URL the production deployment is reachable at. Falls back to the *.pages.dev subdomain when no custom domain is attached."
  value       = var.custom_domain == "" ? "https://${cloudflare_pages_project.frontend.subdomain}" : "https://${var.custom_domain}"
}

# ─── Fly.io API tier (ADR-0007) ────────────────────────────────────────────

output "api_url" {
  description = "Canonical production URL for the JVM API (ADR-0007 §4). Falls back to the *.fly.dev hostname when no custom domain is attached."
  value       = var.custom_domain == "" ? "https://${var.fly_app_name}.fly.dev" : "https://api.${var.custom_domain}"
}

output "fly_app_name" {
  description = "Fly.io app name backing the API (matches the *.fly.dev subdomain root)."
  value       = fly_app.api.name
}

output "fly_app_hostname" {
  description = "Fly.io *.fly.dev hostname for the API app. The Cloudflare CNAME at api.<custom_domain> points here."
  value       = "${fly_app.api.name}.fly.dev"
}

output "fly_postgres_app_name" {
  description = "Fly.io app name reserved for the Postgres cluster. Bootstrapped by `flyctl postgres create` (see `fly-postgres.tf` deviation note); not provider-managed today."
  value       = var.fly_postgres_app_name
}
