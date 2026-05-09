# k8s cluster module — provider-agnostic interface (ADR-0009).
#
# This file documents the contract and invokes the active provider
# implementation. Concrete cloud resources live in
# `terraform/k8s/providers/<name>/`; this root only wires inputs.
#
# The contract:
#
#   1. Every implementation under `providers/<name>/` declares the same
#      input variables as `variables.tf` here (cluster_name, region,
#      control_plane_count, worker_count, node_size, ssh_public_keys,
#      k3s_version).
#
#   2. Every implementation produces the same outputs as `outputs.tf` here
#      (kubeconfig, kubeconfig_path, cluster_endpoint, cluster_ca_certificate,
#      node_ips), with the same sensitivity flags.
#
#   3. Provider-specific knobs (network zones, placement groups, image IDs)
#      live as private variables inside the provider module — never leak
#      out into this contract.
#
# Swapping providers means swapping the directory the root module invokes:
#
#     module "cluster" {
#       source = "./k8s/providers/hetzner"   # ← change this line only
#       # ...same variable assignments either way
#     }
#
# Application code, the bootstrap Helm/Flux layer, and any kubectl-based
# tooling read only the outputs above and so are provider-blind.
#
# Provider implementations:
#   - providers/hetzner/   — first implementation (this PR, ADR-0009 §default)
#   - providers/scaleway/  — documented swap target (no PR scheduled)
#   - providers/ovh/       — documented swap target (no PR scheduled)
#
# Bootstrap of in-cluster operators (cert-manager, external-dns,
# ingress-nginx, CloudNative-PG) is NOT this module's concern — that lives
# in a separate Helm/Flux layer in a later PR. This module produces a
# kubeconfig-ready cluster and stops there.

module "cluster" {
  source = "./providers/hetzner"

  cluster_name        = var.cluster_name
  region              = var.region
  control_plane_count = var.control_plane_count
  worker_count        = var.worker_count
  node_size           = var.node_size
  worker_node_size    = var.worker_node_size
  ssh_public_keys     = var.ssh_public_keys
  k3s_version         = var.k3s_version
}
