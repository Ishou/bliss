# Hetzner Cloud implementation of the k8s cluster contract (ADR-0009 §2).
# v1: 1 control plane + 1 worker, k3s via cloud-init. Kubeconfig retrieval
# is a documented one-time human step (ADR-0009 §10) — see README.

# Cluster join token shared by the control-plane (k3s server) and worker
# (k3s agent) cloud-init templates via K3S_TOKEN. 48 chars of [a-zA-Z0-9]
# is enough entropy and stays printable for embedding in YAML user-data.
resource "random_password" "k3s_token" {
  length  = 48
  special = false
}
