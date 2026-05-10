# ADR-0035: Declare worker floating-IP alias via netplan, not runtime systemd

## Status

Accepted.

## Context

The k3s cluster (ADR-0009 §2) routes all public ingress and the kubeconfig
server URL through a single Hetzner Cloud Floating IP (`116.202.180.82` in
prod, see `terraform/k8s/providers/hetzner/floating-ip.tf`). The worker
node is the FIP's assigned holder (see `hcloud_floating_ip_assignment.ingress`),
so Hetzner routes FIP-destined packets to the worker at the network
layer. From there:

- Port 6443 traffic is DNAT'd to the control plane's private IP so
  `kubectl` against the FIP lands on the k3s API server.
- Ports 80/443 are served directly by the ingress-nginx controller,
  which runs on the worker with `hostNetwork: true`.

For the worker's kernel to accept FIP-destined packets locally (instead
of dropping them as not-for-me), the FIP must be aliased on `eth0`.
Until this ADR, both the alias and the iptables DNAT were configured
by `floating-ip-config.service`, a `oneshot` systemd unit invoked at
every boot. Step 1 of its script ran:

```sh
ip addr add ${floating_ip}/32 dev eth0 2>/dev/null || true
```

On 2026-05-10 the prod worker silently lost this alias — the alias is
runtime-only state, not declared anywhere netplan or systemd-networkd
knows about, so any reapply of the netplan config (DHCP renewal,
package upgrade touching `/etc/netplan/`, manual `netplan apply`,
cloud-init network module re-running) wipes it. The kernel then
dropped FIP-destined packets to ports 80/443, taking down every public
endpoint behind ingress (`api.wordsparrow.io`, `errors.wordsparrow.io`,
`signoz.wordsparrow.io`, `matomo.wordsparrow.io`).

Critically, port 6443 stayed up throughout the outage because the
iptables PREROUTING DNAT rule fires before the routing decision and
does not require the destination IP to be local. So `kubectl` worked,
node status was healthy, ingress pods were `Running`, the controller
service was reachable inside the cluster — every cluster-internal
signal said "fine" while the public surface was dark. Detection came
from a human noticing, not from a probe. See
`docs/incidents/2026-05-10-floating-ip-alias-wiped.md` for the full
timeline and recovery.

## Decision

Move the FIP alias out of the runtime systemd unit and into a netplan
dropin written via cloud-init's `write_files`:

```yaml
# /etc/netplan/60-floating-ip.yaml (worker only, mode 0600)
network:
  version: 2
  ethernets:
    eth0:
      addresses:
        - <floating-ip>/32
```

`netplan apply` is invoked once during cloud-init `runcmd` so the
dropin takes effect at first boot. From that point forward the alias
is part of the declared network state: any subsequent `netplan apply`
or `systemd-networkd` reload reapplies the alias instead of wiping it,
because the alias **is** the desired state.

The systemd unit (`floating-ip-config.service`) is retained but its
scope is reduced to the iptables PREROUTING/POSTROUTING NAT rules for
port 6443. Its `Description=` and inline comments are updated to
reflect this. The unit no longer touches `ip addr`.

## Consequences

**Easier:**

- The FIP alias survives every netplan/networkd reapply by
  construction, eliminating the failure mode from the 2026-05-10
  incident.
- Network state is fully declarative: `cat /etc/netplan/*.yaml` is
  the source of truth for what addresses eth0 owns. No need to
  `journalctl -u floating-ip-config.service` to understand the
  worker's address state.
- Adding more aliases (e.g. an IPv6 FIP for ADR-0009 §10) follows
  the same pattern — drop another netplan file, no script changes.

**Harder:**

- The cloud-init template now writes two files for FIP setup
  (`/etc/netplan/60-floating-ip.yaml` and
  `/usr/local/bin/floating-ip-config.sh`) instead of one.
- A regression test (`terraform/k8s/providers/hetzner/tests/cloud-init/worker.tftest.hcl`)
  pins the new shape so a refactor cannot silently regress to a
  runtime alias. Future changes to the worker template must keep
  this test green.

**Different:**

- Existing prod workers were patched manually (`systemctl restart
  floating-ip-config.service`) to recover from the incident. They
  still run the old unit's alias step. Rolling them through a
  reprovision (`taint + tofu apply`) is the way to converge them on
  the new declarative path; this is non-urgent because the manual
  fix restored service and a future netplan reapply on the existing
  worker would still rerun the old script and re-add the alias on
  any reboot, just not on a netplan reapply alone.

**Out of scope (tracked separately):**

- The iptables NAT rules are still runtime-applied via the systemd
  unit and are subject to the same class of failure (`iptables -F`,
  package upgrade rewriting rules, etc.). Migrating them to
  declarative `nftables` config is a follow-up — they have not bitten
  us yet, and the blast radius is narrower (loss of `kubectl` on the
  FIP, but cluster-internal traffic and public 80/443 keep working).
- An external blackbox probe (Uptime Kuma / GitHub Actions cron
  hitting `https://api.wordsparrow.io/health` from outside Hetzner)
  is the right detection layer for this class of outage — also a
  follow-up, see the post-mortem action items.
