# Cloudflare DNS records managed by the root Terraform module.
#
# Historically this file declared a `cloudflare_dns_record.api_subdomain`
# CNAME pointing `api.<custom_domain>` at the Fly app from ADR-0007.
# Per ADR-0009 §8 step 6 (DNS cutover), ownership of `api.<custom_domain>`
# now belongs to the in-cluster `external-dns` operator (ADR-0009 §3),
# which reconciles A + TXT records from `Ingress` host annotations on
# the Hetzner k3s cluster. The TF-managed CNAME has been removed so
# external-dns can claim the record (its TXT ownership marker
# `external-dns/owner=<txt-owner-id>` is the registry entry it writes
# alongside the A record).
#
# Cloudflare Pages-related DNS for the apex `<custom_domain>` and the
# `www.<custom_domain>` alias is *not* managed here either — Cloudflare
# Pages handles those records as part of the custom-domain attachment
# in `terraform/cloudflare-pages-domain.tf` (ADR-0004). This file is
# the stable home for zone-level records that don't belong to Pages or
# to external-dns (e.g. third-party verification TXTs).

# Google Search Console domain-property verification.
# Issued for `wordsparrow.io` from
# https://search.google.com/search-console — see ADR-0035 §"Search Console".
# The token is a public verification string, not a secret; safe to commit.
#
# Gated on the same `cloudflare_zone_id`/`custom_domain` non-empty pair as
# the rest of the DNS-side IaC so a bootstrap apply (before the zone
# exists) is still a no-op. The record is written at the apex (`name =
# var.custom_domain`) because Search Console verifies domain properties
# against the apex TXT, not a `_google` subdomain.
resource "cloudflare_dns_record" "google_site_verification" {
  count = var.custom_domain == "" || var.cloudflare_zone_id == "" ? 0 : 1

  zone_id = var.cloudflare_zone_id
  name    = var.custom_domain
  type    = "TXT"
  content = "google-site-verification=sXNHgDIo3MlV64qSSTQMBN1HdvLSiYJZpcCE0HH3Cn0"
  ttl     = 1 # Auto
}
