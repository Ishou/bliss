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
# intentionally empty of resources; it remains as a stable home for
# any future zone-level records that don't belong to Pages or to
# external-dns. Delete the file the next time it stays empty across a
# release.
