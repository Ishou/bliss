# Cluster nodes. v1 supports control_plane_count = 1 only; >1 will run
# multiple `cluster-init: true` servers which k3s rejects (HA is a
# follow-up). Image slug `ubuntu-24.04` tracks the newest LTS revision.

locals {
  cp_user_data = [
    for i in range(var.control_plane_count) :
    templatefile("${path.module}/cloud-init/control-plane.yaml.tftpl", {
      cluster_name = var.cluster_name
      k3s_version  = var.k3s_version
      k3s_token    = random_password.k3s_token.result
      tls_san      = "${var.cluster_name}-cp-${i}"
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

  network {
    network_id = hcloud_network.cluster.id
  }

  labels = {
    cluster = var.cluster_name
    role    = "control-plane"
  }

  depends_on = [
    hcloud_network_subnet.cluster,
    hcloud_firewall.cluster,
  ]
}

resource "hcloud_server" "worker" {
  count = var.worker_count

  name        = "${var.cluster_name}-worker-${count.index}"
  server_type = var.node_size
  image       = "ubuntu-24.04"
  location    = var.region

  ssh_keys     = [for k in hcloud_ssh_key.operators : k.id]
  firewall_ids = [hcloud_firewall.cluster.id]

  user_data = templatefile("${path.module}/cloud-init/worker.yaml.tftpl", {
    cluster_name = var.cluster_name
    k3s_version  = var.k3s_version
    k3s_token    = random_password.k3s_token.result
    cp_ip        = hcloud_server.control_plane[0].ipv4_address
  })

  network {
    network_id = hcloud_network.cluster.id
  }

  labels = {
    cluster = var.cluster_name
    role    = "worker"
  }

  depends_on = [hcloud_server.control_plane]
}
