# Cloudflare DNS records for the Fly-backed API (ADR-0007 §4).
#
# `api.wordsparrow.io` is a CNAME pointing at the Fly app's *.fly.dev
# hostname in **DNS-only mode** (`proxied = false`, gray-cloud). Two
# reasons:
#
#   1. SSE / WebSocket. ADR-0007 §9 commits to SSE for v1 and reserves
#      WebSocket for the multiplayer feature. Cloudflare's free-tier
#      proxy has had SSE buffering quirks and a 100s WebSocket
#      idle-timeout; bypassing the proxy avoids surprise cuts.
#   2. TLS at Fly. Fly issues the Let's Encrypt cert for the custom
#      hostname (`fly_cert.api_hostname`); DNS-only mode lets the cert
#      validate against the real origin without a Cloudflare-Universal-
#      SSL detour.
#
# Cloudflare provider v5 schema requires the zone ID directly (the
# singular `data "cloudflare_zone"` source needs `zone_id`, not `name`).
# We accept the zone ID as a non-secret variable
# (`var.cloudflare_zone_id`) — same pattern as `cloudflare_account_id`.
#
# Required Cloudflare API token scope (extends the ADR-0004 token):
# `Zone -> DNS -> Edit` and `Zone -> Zone -> Read` on wordsparrow.io.
# See `docs/deploy.md` for the token-update step.
#
# Gated on var.custom_domain *and* var.cloudflare_zone_id so a
# bootstrap apply before either exists still works.

resource "cloudflare_dns_record" "api_subdomain" {
  count = var.custom_domain == "" || var.cloudflare_zone_id == "" ? 0 : 1

  zone_id = var.cloudflare_zone_id
  name    = "api.${var.custom_domain}"
  type    = "CNAME"
  # Fly's per-app *.fly.dev hostname is the canonical CNAME target.
  # Using the variable rather than a Fly provider attribute keeps the
  # record creatable even if the Fly provider is temporarily
  # unreachable — the convention `<app>.fly.dev` is stable.
  content = "${var.fly_app_name}.fly.dev"
  ttl     = 300
  proxied = false
  comment = "ADR-0007 §4 — DNS-only CNAME to Fly. Managed by terraform/cloudflare-dns-records.tf."
}
