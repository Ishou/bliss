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
  }
}
