# Fly.io app for the WordSparrow JVM API (ADR-0007).
#
# Provider deviation note (binding for this whole file):
#   ADR-0007 §2 names the `fly-apps/fly` Terraform provider. That
#   provider was archived 2024-03-01. We use the active community fork
#   `andrewbaxter/fly` (see `versions.tf` for pin rationale). Resource
#   surface differs from the archived original:
#     - `fly_app`, `fly_volume`, `fly_ip`, `fly_cert` — present.
#     - `fly_machine` — present, but no `checks` / `auto_stop_machines`
#                       schema. Healthcheck and scale config live in
#                       `grid/api/fly.toml`; `flyctl deploy` reconciles
#                       them at deploy time.
#     - `fly_postgres` — absent. Bootstrapped via `flyctl postgres
#                        create` (see `fly-postgres.tf`).
#
# Division of responsibility (per ADR-0007 §3):
#   - Terraform owns: app existence, dedicated IPv4/IPv6, TLS cert,
#                     DNS record. Things that should never differ
#                     between fresh applies.
#   - `fly.toml` + `flyctl deploy` own: services, ports, healthchecks,
#                                       auto-start/auto-stop, scaling.
#                                       Things that change with each
#                                       deploy.

resource "fly_app" "api" {
  name = var.fly_app_name
  org  = var.fly_org
}

# Dedicated IPv4 + IPv6. ADR-0007 §4 requires a stable public address
# the Cloudflare CNAME can target across deploys; Fly's shared-v4 can
# shift. ~$2/month for the dedicated v4 (Fly's standard rate as of
# 2026-04 — verify at apply time). v6 is free.

resource "fly_ip" "api_v4" {
  app  = fly_app.api.name
  type = "v4"
}

resource "fly_ip" "api_v6" {
  app  = fly_app.api.name
  type = "v6"
}

# TLS cert for the API custom hostname. Fly issues + rotates Let's
# Encrypt automatically once DNS validation succeeds (ADR-0007 §4).
# Gated on `var.custom_domain` so a bootstrap apply before the domain
# is registered still works.

resource "fly_cert" "api_hostname" {
  count = var.custom_domain == "" ? 0 : 1

  app      = fly_app.api.name
  hostname = "api.${var.custom_domain}"
}

# API machine envelope. Pinned to var.fly_region (cdg) and the v1
# sizing from ADR-0007 §2 (1× shared-CPU, 512 MB).
#
# `image` is driven by var.fly_machine_image, default "" — meaning
# `flyctl deploy` from CI is the source of truth for the running
# image. The first apply creates no machine; the first CI run on
# `main` creates it implicitly via `flyctl deploy`. Subsequent applies
# with a non-empty `var.fly_machine_image` bring that machine under TF
# management.
#
# Healthchecks (`/v1/health`), auto-start/auto-stop, and the
# 80→8080 / 443→8080 service map all live in `grid/api/fly.toml`.

resource "fly_machine" "api" {
  count = var.fly_machine_image == "" ? 0 : 1

  app    = fly_app.api.name
  region = var.fly_region
  image  = var.fly_machine_image
  name   = "${var.fly_app_name}-machine"

  cpus   = var.fly_machine_cpus
  memory = var.fly_machine_memory_mb

  services = [
    {
      protocol      = "tcp"
      internal_port = 8080
      ports = [
        {
          port        = 80
          handlers    = ["http"]
          force_https = true
        },
        {
          port     = 443
          handlers = ["tls", "http"]
        },
      ]
    },
  ]
}
