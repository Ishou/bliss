# Terraform CLI version pin for the k8s cluster module.
#
# Matches the root `terraform/versions.tf` pin (~> 1.9) so the same CLI is
# usable across the platform IaC tree.
#
# DELIBERATELY no `required_providers` block: this is the provider-agnostic
# interface module (ADR-0009). Provider requirements (e.g. `hetznercloud/hcloud`,
# `scaleway/scaleway`) belong in the provider-specific implementation that
# lives under `providers/<name>/` and ships in a follow-up PR. Declaring
# providers here without a corresponding implementation would create a
# half-state that confuses `terraform init`.

terraform {
  required_version = "~> 1.9"
}
