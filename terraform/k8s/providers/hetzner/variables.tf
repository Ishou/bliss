# Variables mirror `terraform/k8s/variables.tf` exactly (ADR-0009 §2 contract).
# Validations are NOT duplicated here — they run at the parent layer.

variable "cluster_name" {
  description = "k3s cluster name. Forwarded from the parent contract."
  type        = string
}

variable "region" {
  description = "Hetzner location (e.g. fsn1, nbg1, hel1). ADR-0009 default fsn1."
  type        = string
}

variable "control_plane_count" {
  description = "Number of control-plane nodes. v1 supports 1 only."
  type        = number
  default     = 1

  validation {
    condition     = var.control_plane_count == 1
    error_message = "The Hetzner v1 module supports exactly 1 control-plane node. HA (count >= 3) is a future workstream — see ADR-0009 §10."
  }
}

variable "worker_count" {
  description = "Number of dedicated worker nodes."
  type        = number
  default     = 1
}

variable "node_size" {
  description = "Hetzner server type (e.g. cx22, cx32, cpx21). Default cx22 per ADR-0009 §2. Used for control-plane nodes and as the fallback for workers when `worker_node_size` is null."
  type        = string
}

variable "worker_node_size" {
  description = "Optional Hetzner server type for worker nodes (e.g. cx32 to host the SigNoz observability stack while keeping the control plane on cx22). When null, workers inherit `node_size`."
  type        = string
  default     = null
}

variable "ssh_public_keys" {
  description = "OpenSSH public keys uploaded to Hetzner and installed on every node."
  type        = list(string)
}

variable "k3s_version" {
  description = "k3s release tag (vX.Y.Z+k3sN). Forwarded from the parent contract."
  type        = string
}

variable "private_iface" {
  type        = string
  default     = "enp7s0"
  description = <<-EOT
    Private NIC name on cluster nodes. Hetzner CX-series Ubuntu 24.04
    images use enp7s0; CPX/CCX/ARM64 types may differ. Verify with
    `ip -br addr` on a freshly booted instance before overriding.
    Cloud-init's wait loop will hang indefinitely if this is wrong.
  EOT
}
