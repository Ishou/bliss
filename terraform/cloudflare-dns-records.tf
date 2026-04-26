# Cloudflare DNS records for the API subdomain.
#
# Originally defined for the Fly-backed API (ADR-0007 §4). ADR-0007 was
# superseded by ADR-0009 (Hetzner k3s) and the Fly deployment never
# reached production; the Fly teardown PR removed the Fly app, but this
# resource is intentionally retained until ADR-0009 §8 step 6 cuts DNS
# ownership over to in-cluster `external-dns` (which reconciles records
# from `Ingress` host annotations). Until step 6 lands the CNAME below
# resolves to a non-existent Fly app (NXDOMAIN); acceptable because no
# live traffic was ever served via this hostname.
#
# `api.wordsparrow.io` is a CNAME in **DNS-only mode** (`proxied =
# false`, gray-cloud). Two reasons that survive the platform change:
#
#   1. SSE / WebSocket. ADR-0007 §9 (carried into ADR-0009) commits to
#      SSE for v1 and reserves WebSocket for the multiplayer feature.
#      Cloudflare's free-tier proxy has had SSE buffering quirks and a
#      100s WebSocket idle-timeout; bypassing the proxy avoids surprise
#      cuts.
#   2. TLS at the origin. Fly issued the cert under ADR-0007;
#      cert-manager (ADR-0009 §3) will issue it under the new platform.
#      DNS-only mode keeps that path clean either way.
#
# Cloudflare provider v5 schema requires the zone ID directly (the
# singular `data "cloudflare_zone"` source needs `zone_id`, not `name`).
# We accept the zone ID as a non-secret variable
# (`var.cloudflare_zone_id`) — same pattern as `cloudflare_account_id`.
#
# Required Cloudflare API token scope (extends the ADR-0004 token):
# `Zone -> DNS -> Edit` and `Zone -> Zone -> Read` on wordsparrow.io.
#
# Gated on var.custom_domain *and* var.cloudflare_zone_id so a
# bootstrap apply before either exists still works.

resource "cloudflare_dns_record" "api_subdomain" {
  count = var.custom_domain == "" || var.cloudflare_zone_id == "" ? 0 : 1

  zone_id = var.cloudflare_zone_id
  name    = "api.${var.custom_domain}"
  type    = "CNAME"
  # Historical CNAME target from ADR-0007 (the Fly app `wordsparrow-api`
  # at `*.fly.dev`). The Fly app no longer exists, so this CNAME
  # resolves to NXDOMAIN until ADR-0009 §8 step 6 hands ownership of
  # the record to in-cluster `external-dns`. Inlined (not a variable)
  # because the value is a historical artifact, not a configuration
  # input — step 6 will replace this resource entirely.
  content = "wordsparrow-api.fly.dev"
  ttl     = 300
  proxied = false
  comment = "Historical Fly target (ADR-0007, superseded by ADR-0009). Slated for external-dns takeover in ADR-0009 §8 step 6."
}
