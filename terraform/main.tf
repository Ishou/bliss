# Cloudflare provider configuration.
#
# The API token is *not* declared in repo code — it is read from the
# `CLOUDFLARE_API_TOKEN` environment variable by the provider's default
# auth path. This keeps secret material out of `.tf`, `.tfvars`, and state
# diffs (per CLAUDE.md "Secrets never in code. Injected at runtime.").
#
# The account_id is *not* a secret; it is a stable Cloudflare account UUID
# and is supplied via the `cloudflare_account_id` variable so the same
# config can target any account (ephemeral, sandbox, production tenant)
# without code changes.
#
# The maintainer runs:
#   export CLOUDFLARE_API_TOKEN=...
#   terraform -chdir=terraform/ apply -var="cloudflare_account_id=..."
#
# See `terraform/README.md` for the full bootstrap procedure and
# ADR-0004 §3 / Notes for the IaC-as-source-of-truth requirement.

provider "cloudflare" {
  # api_token is read from CLOUDFLARE_API_TOKEN env var by default;
  # explicitly omitted here so the secret never lives in repo or tfvars.
}

# Fly.io provider configuration.
#
# Mirrors the Cloudflare pattern: the API token is read from the
# `FLY_API_TOKEN` environment variable by the provider's default auth path
# (per andrewbaxter/fly provider docs, the `fly_api_token` argument falls
# back to the `FLY_API_TOKEN` env var when unset). Keeping the argument out
# of code means the secret never lands in `.tf`, `.tfvars`, or state diffs
# (CLAUDE.md "Secrets never in code. Injected at runtime.").
#
# Per ADR-0007 §8, the token is created once via
# `flyctl tokens create deploy --app wordsparrow-api` and scoped to the
# single app — not org-wide.
#
# The maintainer runs:
#   export FLY_API_TOKEN=...
#   export CLOUDFLARE_API_TOKEN=...
#   tofu -chdir=terraform/ apply -var="cloudflare_account_id=..." \
#                                -var="cloudflare_zone_id=..."
#
# See `terraform/README.md` and `docs/deploy.md` for the full bootstrap.

provider "fly" {
  # fly_api_token is read from FLY_API_TOKEN env var by default;
  # explicitly omitted here so the secret never lives in repo or tfvars.
}
