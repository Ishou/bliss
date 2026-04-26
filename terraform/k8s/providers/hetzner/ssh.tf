# SSH keys uploaded to the Hetzner project, installed on every node by
# cloud-init. Keyed by SHA-256 of the public-key material so re-orders
# don't churn resources and duplicates collapse to one upload.

locals {
  ssh_key_by_fingerprint = {
    for key in var.ssh_public_keys : sha256(key) => key
  }
}

resource "hcloud_ssh_key" "operators" {
  for_each = local.ssh_key_by_fingerprint

  name       = "${var.cluster_name}-${substr(each.key, 0, 12)}"
  public_key = each.value
  labels     = { cluster = var.cluster_name }
}
