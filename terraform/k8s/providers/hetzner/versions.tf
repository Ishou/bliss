# Provider pin for the Hetzner implementation (ADR-0009 §2 — single-vendor
# IaC for v1). `~> 1.51` admits 1.51.x patches but blocks 2.x.

terraform {
  required_version = "~> 1.10"

  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.51"
    }

    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}
