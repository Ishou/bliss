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
}

variable "worker_count" {
  description = "Number of dedicated worker nodes."
  type        = number
  default     = 1
}

variable "node_size" {
  description = "Hetzner server type (e.g. cx22, cx32, cpx21). Default cx22 per ADR-0009 §2."
  type        = string
}

variable "ssh_public_keys" {
  description = "OpenSSH public keys uploaded to Hetzner and installed on every node."
  type        = list(string)
}

variable "k3s_version" {
  description = "k3s release tag (vX.Y.Z+k3sN). Forwarded from the parent contract."
  type        = string
}
