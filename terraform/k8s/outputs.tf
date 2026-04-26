# Outputs the k8s cluster module must produce.
#
# Per ADR-0009 every provider implementation under `providers/<name>/` MUST
# emit exactly these outputs with the same names and types so downstream
# consumers (the planned Helm/Flux bootstrap layer, kubectl/helm CLI use,
# CI smoke tests) bind to the contract — not to a specific provider.
#
# `kubeconfig` and `cluster_ca_certificate` are sensitive: they grant
# cluster-admin and must never appear in plan diffs or CI logs. `kubeconfig`
# is the raw file contents; `kubeconfig_path` is the local path Terraform
# wrote it to (sensitive-aware tooling consumes the path; everything else
# reads structured fields).

output "kubeconfig" {
  description = "Raw kubeconfig YAML for cluster-admin access. Sensitive: contains the embedded client certificate / token. Pipe into KUBECONFIG via `terraform output -raw kubeconfig > ~/.kube/wordsparrow.yaml` rather than echoing. v1 (Hetzner): null — fetched via the documented one-time human step in docs/deploy.md."
  value       = module.cluster.kubeconfig
  sensitive   = true
}

output "kubeconfig_path" {
  description = "Local filesystem path where Terraform wrote the kubeconfig (typically under the module's working directory). Intended for local operator workflows only — CI/CD pipelines should consume the `kubeconfig` content output instead, since runners are ephemeral and the file at this path will not exist there. Path is host-local and not portable across machines. v1: null."
  value       = module.cluster.kubeconfig_path
}

output "cluster_endpoint" {
  description = "Kubernetes API server endpoint URL (e.g. https://1.2.3.4:6443). Used by external CI/CD to construct kubeconfig fragments without exposing the full file."
  value       = module.cluster.cluster_endpoint
}

output "cluster_ca_certificate" {
  description = "PEM-encoded cluster CA certificate. Sensitive only because pairing it with a known endpoint helps an attacker craft a convincing MITM if a client-cert is later leaked; the cert itself is not a credential. Consumers that prefer structured kubeconfig assembly read this instead of parsing `kubeconfig`. v1: null — extracted from the kubeconfig retrieved per docs/deploy.md."
  value       = module.cluster.cluster_ca_certificate
  sensitive   = true
}

output "node_ips" {
  description = "List of public IPv4 addresses for every node (control plane + workers). Used by smoke tests, ssh access, and any external monitoring that pings nodes directly. Order is not guaranteed stable across applies — treat as a set."
  value       = module.cluster.node_ips
}
