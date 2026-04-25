# Custom domain attachment for the Cloudflare Pages project.
#
# Per ADR-0005 §1 (production domain wordsparrow.io) and ADR-0004 §3
# (Cloudflare Pages as the deployment target), the apex `wordsparrow.io`
# and the `www.` subdomain are attached to the Pages project as IaC.
# Cloudflare auto-provisions SSL/TLS once DNS verifies — no manual
# certificate work.
#
# The resources are gated on `var.custom_domain` being non-empty so the
# bootstrap apply (before a domain is registered) still works against an
# empty default. See `docs/deploy.md` for the DNS-side maintainer steps
# the apply triggers.

resource "cloudflare_pages_domain" "apex" {
  count = var.custom_domain == "" ? 0 : 1

  account_id   = var.cloudflare_account_id
  project_name = cloudflare_pages_project.frontend.name
  name         = var.custom_domain
}

resource "cloudflare_pages_domain" "www" {
  count = var.custom_domain == "" || !var.include_www_alias ? 0 : 1

  account_id   = var.cloudflare_account_id
  project_name = cloudflare_pages_project.frontend.name
  name         = "www.${var.custom_domain}"
}
