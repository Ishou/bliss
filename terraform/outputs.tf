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
