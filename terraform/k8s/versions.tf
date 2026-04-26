# Terraform CLI version pin for the k8s cluster module.
#
# Diverges from the root `terraform/versions.tf` pin (~> 1.9) because the
# s3 backend's `use_lockfile` flag below requires TF 1.10+ / OpenTofu 1.10+
# (ADR-0010 §2). The root tree does not use that flag and stays on ~> 1.9.
#
# DELIBERATELY no `required_providers` block: this is the provider-agnostic
# interface module (ADR-0009). Provider requirements (e.g. `hetznercloud/hcloud`,
# `scaleway/scaleway`) belong in the provider-specific implementation that
# lives under `providers/<name>/` and ships in a follow-up PR. Declaring
# providers here without a corresponding implementation would create a
# half-state that confuses `terraform init`.

terraform {
  required_version = "~> 1.10"

  # Remote state on Hetzner Object Storage (FSN1), per ADR-0010 §2.
  # Bucket is bootstrapped out-of-band (one-time human step) —
  # see docs/deploy.md §"Terraform k8s state backend — first-time bootstrap (one-time)".
  backend "s3" {
    bucket = "bliss-tf-state"
    key    = "k8s/terraform.tfstate"
    region = "fsn1"

    endpoints = {
      s3 = "https://fsn1.your-objectstorage.com"
    }

    # Hetzner Object Storage is S3-compatible but not AWS — skip
    # all AWS-only credential / metadata / region lookups.
    skip_credentials_validation = true
    skip_metadata_api_check     = true
    skip_region_validation      = true
    skip_requesting_account_id  = true
    use_path_style              = true

    # Time-limited workaround for OpenTofu issue #2605 — the lock
    # file write hits XAmzContentSHA256Mismatch against Hetzner OS
    # until OpenTofu PR #2606 lands. Remove this line once the
    # fix is in our pinned OpenTofu version.
    skip_s3_checksum = true

    # Native state locking via the lock-object pattern (TF 1.10+ /
    # OpenTofu 1.10+). No DynamoDB equivalent is needed against
    # Hetzner OS because conditional writes (If-None-Match) are
    # honored — see ADR-0010 §2.
    use_lockfile = true
  }
}
