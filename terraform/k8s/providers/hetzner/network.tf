# Private network: one /16 with a /24 subnet in the configured location.
# Hetzner groups locations into network zones (FSN1/NBG1/HEL1 are all
# eu-central); explicit lookup beats string-prefix heuristics.

locals {
  network_zones = {
    fsn1 = "eu-central"
    nbg1 = "eu-central"
    hel1 = "eu-central"
    ash  = "us-east"
    hil  = "us-west"
    sin  = "ap-southeast"
  }
  network_zone = lookup(local.network_zones, var.region, "eu-central")
}

resource "hcloud_network" "cluster" {
  name     = "${var.cluster_name}-net"
  ip_range = "10.0.0.0/16"
  labels   = { cluster = var.cluster_name }
}

resource "hcloud_network_subnet" "cluster" {
  network_id   = hcloud_network.cluster.id
  type         = "cloud"
  network_zone = local.network_zone
  ip_range     = "10.0.1.0/24"
}
