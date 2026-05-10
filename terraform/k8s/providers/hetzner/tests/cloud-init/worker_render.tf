# Test fixture: renders ../../cloud-init/worker.yaml.tftpl with stub
# variables so worker.tftest.hcl can grep the output for invariants.
# This module has no resources and never runs `tofu apply` — it exists
# solely as a target for `tofu test`. Production rendering happens in
# ../../server.tf via the same templatefile() call.

terraform {
  required_version = "~> 1.10"
}

variable "cluster_name" {
  type    = string
  default = "test-cluster"
}

variable "k3s_version" {
  type    = string
  default = "v1.35.3+k3s1"
}

variable "k3s_token" {
  # Not marked sensitive: this is a test fixture and the output is
  # consumed by `tofu test` assertions that need to read the rendered
  # body. Production rendering in ../../server.tf passes a sensitive
  # value (random_password.k3s_token.result) instead.
  type    = string
  default = "stub-token"
}

variable "cp_ip" {
  type    = string
  default = "10.0.1.10"
}

variable "private_ip" {
  type    = string
  default = "10.0.1.20"
}

variable "private_iface" {
  type    = string
  default = "enp7s0"
}

variable "floating_ip" {
  type    = string
  default = "203.0.113.42"
}

output "worker_rendered" {
  value = templatefile("${path.module}/../../cloud-init/worker.yaml.tftpl", {
    cluster_name  = var.cluster_name
    k3s_version   = var.k3s_version
    k3s_token     = var.k3s_token
    cp_ip         = var.cp_ip
    private_ip    = var.private_ip
    private_iface = var.private_iface
    floating_ip   = var.floating_ip
  })
}
