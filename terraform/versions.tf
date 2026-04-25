# Terraform + provider version pins for the Bliss platform IaC.
#
# Pin policy (per CLAUDE.md "Lock files committed. Container images pinned to
# digest. Builds are deterministic."): the Terraform CLI is pinned to a minor
# range and the Cloudflare provider is pinned to a minor range so patch-level
# bug fixes flow in but breaking changes (provider major) do not.
#
# Provider lockfile (`.terraform.lock.hcl`) is committed by the maintainer
# after the first `terraform init`; that file is the digest-equivalent for
# Terraform providers.

terraform {
  required_version = "~> 1.9"

  required_providers {
    # cloudflare/cloudflare v5.x is the current major as of 2026-04;
    # v4 is in maintenance only. Pinning to ~> 5.19 means any 5.x patch or
    # minor flows in, 6.x does not without a deliberate bump.
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.19"
    }

    # andrewbaxter/fly is the active community fork of the now-archived
    # fly-apps/fly provider (fly-apps was archived 2024-03-01, last release
    # v0.0.23). ADR-0007 §2 names "fly-apps/fly" by intent — the fork is the
    # operational successor. v0.1.18 (2024-10-28) is the latest release as of
    # 2026-04. Pinning ~> 0.1 keeps any 0.1.x patch flowing in; an eventual
    # 0.2 bump is a deliberate decision (small breaking surface — only `app`,
    # `machine`, `volume`, `ip`, `cert` resources, no `postgres`).
    #
    # Verify the latest at apply time: `tofu init` will pick the highest
    # 0.1.x available and pin it in `.terraform.lock.hcl`.
    fly = {
      source  = "andrewbaxter/fly"
      version = "~> 0.1"
    }
  }
}
