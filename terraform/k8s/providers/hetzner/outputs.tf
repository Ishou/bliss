# Outputs satisfy `terraform/k8s/outputs.tf`. v1: kubeconfig and CA are
# `null` per ADR-0009 §10's documented-one-time-human-step pattern; the
# maintainer fetches the kubeconfig via scp post-apply (docs/deploy.md).

output "kubeconfig" {
  description = "Raw kubeconfig YAML. v1: null — fetched manually per docs/deploy.md."
  value       = null
  sensitive   = true
}

output "kubeconfig_path" {
  description = "Local path of the kubeconfig. v1: null."
  value       = null
}

output "cluster_endpoint" {
  description = "k3s API server URL (https://<control-plane-ip>:6443)."
  value = (
    var.control_plane_count > 0
    ? "https://${hcloud_server.control_plane[0].ipv4_address}:6443"
    : null
  )
}

output "cluster_ca_certificate" {
  description = "PEM cluster CA. v1: null — extracted from the kubeconfig."
  value       = null
  sensitive   = true
}

output "node_ips" {
  description = "Public IPv4 of every node (control plane + workers)."
  value = concat(
    [for s in hcloud_server.control_plane : s.ipv4_address],
    [for s in hcloud_server.worker : s.ipv4_address],
  )
}

output "ingress_floating_ip" {
  description = "Stable public IPv4 attached to the ingress node. Survives node replacement; used as the kubeconfig server URL, the k3s API tls-san entry, and ingress-nginx publish-status-address. Consume via `tofu output -raw ingress_floating_ip`."
  value       = hcloud_floating_ip.ingress.ip_address
}
