# Cluster nodes. v1 supports control_plane_count = 1 only; >1 will run
# multiple `cluster-init: true` servers which k3s rejects (HA is a
# follow-up). Image slug `ubuntu-24.04` tracks the newest LTS revision.
#
# Each server is attached to the cluster's private network with a
# deterministic IP via `hcloud_server_network`. We need the IP at
# plan-time so the cloud-init template can pin k3s `node-ip` and
# `flannel-iface` to the private interface — without that, k3s
# advertises the public IP and flannel's vxlan tries to traverse the
# Hetzner firewall, which scopes intra-cluster UDP/TCP to 10.0.0.0/16
# and silently drops cross-node pod traffic.

locals {
  # Private-network IPs inside the 10.0.1.0/24 subnet. We reserve
  # .10..(10+N) for control planes and .20..(20+N) for workers so the
  # ranges never collide as either count grows.
  cp_private_ips     = [for i in range(var.control_plane_count) : "10.0.1.${10 + i}"]
  worker_private_ips = [for i in range(var.worker_count) : "10.0.1.${20 + i}"]

  # Worker server type — falls back to the contract's `node_size` when
  # the optional override is unset. Splitting the size between control
  # plane and worker (rather than bumping `node_size` for everything)
  # is per MANIFESTO §Environmental Awareness — right-size, do not
  # over-provision the control plane just because the worker grew.
  effective_worker_node_size = coalesce(var.worker_node_size, var.node_size)

  cp_user_data = [
    for i in range(var.control_plane_count) :
    templatefile("${path.module}/cloud-init/control-plane.yaml.tftpl", {
      cluster_name  = var.cluster_name
      k3s_version   = var.k3s_version
      k3s_token     = random_password.k3s_token.result
      tls_san       = "${var.cluster_name}-cp-${i}"
      private_ip    = local.cp_private_ips[i]
      private_iface = var.private_iface
      # Floating IP must be in tls-san so the kubeconfig — whose
      # server URL is the floating IP, not the CP's ephemeral public
      # IP — passes TLS verification. See floating-ip.tf for the
      # circular-reference analysis (none: ip_address is allocated
      # before any server exists).
      floating_ip = hcloud_floating_ip.ingress.ip_address
    })
  ]
}

resource "hcloud_server" "control_plane" {
  count = var.control_plane_count

  name        = "${var.cluster_name}-cp-${count.index}"
  server_type = var.node_size
  image       = "ubuntu-24.04"
  location    = var.region

  ssh_keys     = [for k in hcloud_ssh_key.operators : k.id]
  firewall_ids = [hcloud_firewall.cluster.id]
  user_data    = local.cp_user_data[count.index]

  labels = {
    cluster = var.cluster_name
    role    = "control-plane"
  }

  depends_on = [
    hcloud_network_subnet.cluster,
    hcloud_firewall.cluster,
  ]
}

resource "hcloud_server_network" "control_plane" {
  count = var.control_plane_count

  server_id  = hcloud_server.control_plane[count.index].id
  network_id = hcloud_network.cluster.id
  ip         = local.cp_private_ips[count.index]
}

resource "hcloud_server" "worker" {
  count = var.worker_count

  name        = "${var.cluster_name}-worker-${count.index}"
  server_type = local.effective_worker_node_size
  image       = "ubuntu-24.04"
  location    = var.region

  ssh_keys     = [for k in hcloud_ssh_key.operators : k.id]
  firewall_ids = [hcloud_firewall.cluster.id]

  user_data = templatefile("${path.module}/cloud-init/worker.yaml.tftpl", {
    cluster_name  = var.cluster_name
    k3s_version   = var.k3s_version
    k3s_token     = random_password.k3s_token.result
    cp_ip         = local.cp_private_ips[0]
    private_ip    = local.worker_private_ips[count.index]
    private_iface = var.private_iface
    # Floating IP must be configured as an alias on the worker's public
    # interface and DNAT'd for k3s API traffic — the worker is the FIP's
    # assigned holder (see `hcloud_floating_ip_assignment.ingress`) so
    # any FIP traffic arrives here at the kernel level. Without the alias
    # the kernel drops the packets as not-for-me; without DNAT, port
    # 6443 traffic has nowhere to go because k3s runs on the CP.
    floating_ip = hcloud_floating_ip.ingress.ip_address
  })

  labels = {
    cluster = var.cluster_name
    role    = "worker"
  }

  depends_on = [
    hcloud_server.control_plane,
    hcloud_server_network.control_plane,
  ]
}

resource "hcloud_server_network" "worker" {
  count = var.worker_count

  server_id  = hcloud_server.worker[count.index].id
  network_id = hcloud_network.cluster.id
  ip         = local.worker_private_ips[count.index]
}
