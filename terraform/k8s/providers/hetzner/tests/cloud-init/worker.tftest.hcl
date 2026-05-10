# Regression coverage for the 2026-05-10 outage: the worker's runtime
# `ip addr add ${floating_ip}/32 dev eth0` alias was wiped (likely by
# a netplan/networkd reapply) and the ingress floating IP went dark on
# 80/443 while the 6443 DNAT rule kept working. The post-mortem
# (docs/incidents/2026-05-10-floating-ip-alias-wiped.md) and ADR-0035
# moved the alias from a runtime systemd step to a declarative netplan
# dropin so any future netplan reapply preserves it.
#
# These assertions pin the new shape so a refactor cannot silently
# regress to the runtime-only alias.

variables {
  floating_ip = "203.0.113.42"
  cp_ip       = "10.0.1.10"
}

run "worker_cloud_init_declares_fip_via_netplan" {
  command = plan

  assert {
    condition     = strcontains(output.worker_rendered, "/etc/netplan/60-floating-ip.yaml")
    error_message = "worker cloud-init must write /etc/netplan/60-floating-ip.yaml so the FIP alias survives netplan/networkd reloads (ADR-0035)."
  }

  assert {
    condition     = strcontains(output.worker_rendered, "addresses:")
    error_message = "netplan dropin must declare an `addresses:` list — without it the FIP is not aliased on eth0."
  }

  assert {
    condition     = strcontains(output.worker_rendered, "${var.floating_ip}/32")
    error_message = "netplan dropin must include the floating IP as a /32 entry so the kernel accepts FIP traffic locally."
  }

  assert {
    condition     = strcontains(output.worker_rendered, "netplan apply")
    error_message = "runcmd must invoke `netplan apply` at first boot so the FIP dropin takes effect before k3s-agent comes up (cloud-init runs runcmd in order, after write_files)."
  }

  assert {
    condition     = !strcontains(output.worker_rendered, "ip addr add ${var.floating_ip}/32 dev eth0")
    error_message = "the runtime `ip addr add` alias step is forbidden — it is exactly what the 2026-05-10 incident proved fragile (see ADR-0035). Manage the alias declaratively via netplan."
  }

  assert {
    condition     = strcontains(output.worker_rendered, "iptables -t nat") && strcontains(output.worker_rendered, "PREROUTING")
    error_message = "the iptables PREROUTING DNAT rule (FIP:6443 -> CP:6443) must remain — kubectl access on the FIP depends on it."
  }
}
