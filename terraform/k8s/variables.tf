# Input variables for the k8s cluster module (provider-agnostic interface).
#
# Per ADR-0009, every provider implementation under `providers/<name>/` MUST
# declare these same variables with the same types and defaults so the root
# module can swap implementations by changing the invoked source path —
# nothing else.
#
# Variables here are deliberately the *contract*, not provider knobs. Anything
# Hetzner-specific (e.g. placement group strategy, network zone) lives inside
# `providers/hetzner/variables.tf` as that implementation's private inputs.
#
# Note on `node_size`: this is a string (not an enum) because the valid set
# is provider-coupled by design — `cx22` for Hetzner, `DEV1-S` for Scaleway,
# `b2-7` for OVH. The README documents this coupling. The alternative —
# normalising sizes into an abstract t-shirt enum — adds a translation layer
# that hides the actual cost/perf knobs and breaks down the moment a provider
# offers a tier without an obvious peer.

variable "cluster_name" {
  description = "k3s cluster name. Used in node tags, the kubeconfig context name, and any provider-side resource naming. Lowercase alphanumeric and hyphens, 2-40 chars."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{0,38}[a-z0-9]$", var.cluster_name))
    error_message = "cluster_name must be 2-40 chars, lowercase alphanumeric or hyphen, and start/end with alphanumeric."
  }
}

variable "region" {
  description = "Provider-region identifier where cluster nodes are created. Semantics defined per implementation (e.g. `nbg1`/`fsn1` for Hetzner, `fr-par-1` for Scaleway). Required because the choice has latency, sovereignty, and cost implications that should be explicit in the root module."
  type        = string

  validation {
    condition     = length(var.region) > 0
    error_message = "region must not be empty."
  }
}

variable "control_plane_count" {
  description = "Number of control-plane nodes. Defaults to 1: v1 runs a single-node control plane (no HA, no etcd quorum). HA control plane (3 or 5 nodes with embedded etcd) is a deliberate later concern — see ADR-0009."
  type        = number
  default     = 1

  validation {
    condition     = var.control_plane_count == 1 || (var.control_plane_count >= 3 && var.control_plane_count % 2 == 1)
    error_message = "control_plane_count must be 1 (single-node) or an odd number >= 3 (HA with embedded etcd quorum). Even counts are invalid for k3s embedded etcd."
  }
}

variable "worker_count" {
  description = "Number of dedicated worker nodes. In single-node mode (control_plane_count = 1, worker_count = 0) the control plane also runs workloads — k3s does not taint the server node by default. Defaults to 1 so the typical small deployment has one server + one agent."
  type        = number
  default     = 1

  validation {
    condition     = var.worker_count >= 0 && var.worker_count <= 50
    error_message = "worker_count must be between 0 and 50. Larger fleets need an HA control plane and a different cost model — out of scope for v1."
  }
}

variable "node_size" {
  description = "Provider-specific instance class identifier (e.g. `cx22` for Hetzner, `DEV1-S` for Scaleway, `b2-7` for OVH). String-typed by design: the valid set is provider-coupled and an abstract t-shirt enum would hide real cost/perf knobs. See README §provider-swap. Acts as the size for control-plane nodes and the default for worker nodes when `worker_node_size` is not set."
  type        = string

  validation {
    condition     = length(var.node_size) > 0
    error_message = "node_size must not be empty. Refer to the active provider's instance-type catalogue."
  }
}

variable "worker_node_size" {
  description = "Optional override for worker-node instance class. When `null` (the default), workers fall back to `var.node_size`. Provided so the worker can be sized larger than the control plane to host stateful workloads (the observability stack — see ADR-0027 — is the first concrete consumer) without paying the same uplift on the control plane, which only runs the k3s API and embedded etcd."
  type        = string
  default     = null

  validation {
    condition     = var.worker_node_size == null || length(var.worker_node_size) > 0
    error_message = "worker_node_size must be null (inherit node_size) or a non-empty provider instance-class identifier."
  }
}

variable "ssh_public_keys" {
  description = "List of SSH public keys (full openssh format, one entry per key) installed on every node for admin access. At least one key is required so the maintainer can reach a node when the k8s API path is broken. ed25519 is preferred; ssh-rsa is accepted for legacy keys but discouraged (OpenSSH 8.8+ disabled it by default due to SHA-1 collision risk)."
  type        = list(string)

  validation {
    condition     = length(var.ssh_public_keys) >= 1
    error_message = "ssh_public_keys must contain at least one key — emergency node access depends on it."
  }

  validation {
    condition     = alltrue([for k in var.ssh_public_keys : can(regex("^(ssh-(rsa|ed25519)|ecdsa-sha2-[a-z0-9-]+) ", k))])
    error_message = "each ssh_public_keys entry must start with a valid openssh key type (ssh-rsa, ssh-ed25519, or ecdsa-sha2-*)."
  }
}

variable "k3s_version" {
  description = "k3s release tag installed on every node (format `vX.Y.Z+k3sN`). Default `v1.35.3+k3s1` was the latest tagged stable release on github.com/k3s-io/k3s/releases/latest at the time this skeleton was written (April 2026). Bump deliberately when a newer minor enters the k3s stable channel; verify the tag exists upstream before changing. Per CLAUDE.md determinism rule, version pins are reviewed (not auto-floated)."
  type        = string
  default     = "v1.35.3+k3s1"

  validation {
    condition     = can(regex("^v[0-9]+\\.[0-9]+\\.[0-9]+\\+k3s[0-9]+$", var.k3s_version))
    error_message = "k3s_version must match the upstream tag format vX.Y.Z+k3sN (e.g. v1.35.3+k3s1)."
  }
}
