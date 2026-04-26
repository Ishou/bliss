# Stable public IPv4 for the cluster's ingress + k3s API server.
#
# Without a floating IP, both the kubeconfig server URL and the public
# DNS records that external-dns publishes point at a node's ephemeral
# public address — which changes the moment a node is replaced (taint
# + apply, k3s upgrade, hardware swap). Re-issuing the kubeconfig and
# rewriting DNS for every node lifecycle event is the kind of tribal-
# knowledge gap CLAUDE.md / ADR-0009 §10 explicitly tries to retire.
#
# A Hetzner Cloud Floating IP is a separately-allocated v4 address
# whose assignment can be moved between servers in the same location
# with a single API call. The address survives node replacement; only
# the assignment changes. We use it for three things:
#
#   1. kubectl access — the kubeconfig server URL is rewritten to the
#      floating IP (docs/deploy.md §"Fetch the kubeconfig").
#   2. k3s API server TLS — the floating IP is added to `tls-san` in
#      cloud-init/control-plane.yaml.tftpl so the API cert covers it.
#   3. ingress-nginx `publish-status-address` — external-dns reads the
#      Ingress status to decide which DNS target to write, so pinning
#      the status address to the floating IP removes the need for a
#      per-Ingress `external-dns.alpha.kubernetes.io/target` annotation.
#
# Default assignment: the worker node. v1 runs ingress-nginx with
# hostNetwork=true (infra/platform/values.yaml) and the worker is the
# node that hosts the controller pods. If the deploy footprint changes
# (HA control plane, dedicated ingress node), update the assignment
# here — there is no other source of truth.
#
# Circular-reference note: `hcloud_floating_ip.ingress.ip_address` is
# allocated at create time independently of any server, so the control-
# plane `templatefile()` reference does NOT cycle through
# `hcloud_floating_ip_assignment.ingress` (which is the resource that
# binds the IP to a server). Plan order: floating_ip → control_plane
# user_data render → control_plane create → worker create → assignment.

resource "hcloud_floating_ip" "ingress" {
  type          = "ipv4"
  home_location = var.region
  name          = "${var.cluster_name}-ingress"
  description   = "Stable public IP for ${var.cluster_name} ingress + k3s API. Survives node replacement."

  labels = {
    cluster = var.cluster_name
    role    = "ingress"
  }
}

resource "hcloud_floating_ip_assignment" "ingress" {
  floating_ip_id = hcloud_floating_ip.ingress.id
  server_id      = hcloud_server.worker[0].id
}
