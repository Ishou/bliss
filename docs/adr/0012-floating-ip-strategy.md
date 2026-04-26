# ADR-0012: Hetzner Floating IP for the cluster's stable public endpoint

## Status

Accepted. Companion to PR #70 (the implementation).

## Context

ADR-0009 §10 flagged that secrets-bootstrap was a documented one-time
human step so it would not become "the next `flyctl postgres
create`-style tribal-knowledge gap". The cluster's **public IP** has
the same shape of gap. Without a stable public endpoint, the
kubeconfig server URL and every external-dns A record point at a
single node's ephemeral public IP. Replacing that node — taint+apply,
k3s upgrade, hardware swap, or a Hetzner-side recreation — forces a
re-issued kubeconfig, a manual `KUBECONFIG_PROD` secret update, and
per-Ingress `external-dns.alpha.kubernetes.io/target` annotation
rewrites. Every step is doc-able, but the doc is long, the failure
mode is "prod is unreachable until the maintainer finishes", and the
manifesto's "no tribal knowledge" rule is the one that keeps slipping.

The 2026-04-26 prod deploy hit this concretely. Server recreation
forced the maintainer to re-fetch the kubeconfig, rotate
`KUBECONFIG_PROD`, and edit per-Ingress annotations on every chart
that publishes a public hostname. That is exactly the manual,
fragile, fan-out-on-every-node-event posture ADR-0009 §10 was
written to avoid for secrets and that the same paragraph anticipates
for "future tribal-knowledge gaps".

Hetzner Cloud Floating IPs are cheap (~€1/month for IPv4),
region-bound, reassignable to any server in the region via one API
call, and survive node lifecycle events. They are a Terraform
primitive (`hcloud_floating_ip` + `hcloud_floating_ip_assignment`),
so the choice is fully IaC — no manifesto carve-out required. They
are also the canonical Hetzner answer for "stable public endpoint in
front of cattle nodes"; we are not inventing a pattern.

## Decision

### 1. Reserve a Hetzner Floating IP at cluster-create time

The `terraform/k8s/providers/hetzner/` module reserves an
`hcloud_floating_ip` (IPv4, region-bound to `var.region`, named
`${cluster_name}-ingress`). It is a first-class cloud resource owned
by the cluster's Terraform root, not a property of any single server.
The provider module re-exports the address as
`ingress_floating_ip`; the parent k8s module re-exports it under the
same name so downstream consumers stay provider-blind (the same
shape as the rest of ADR-0009's provider-agnostic interface).

### 2. Assignment: bound to `hcloud_server.worker[0]` for v1

`hcloud_floating_ip_assignment.ingress` binds the IP to
`hcloud_server.worker[0]`. v1 is single-CP + single-worker, so the
worker is the natural ingress host — the control plane is reserved
for system workloads (ADR-0009 §8). If the cluster grows (HA CP, a
dedicated ingress node, multiple workers), the only Terraform change
required is updating the `hcloud_floating_ip_assignment.ingress.server_id`
reference to point at the new ingress node. No app manifest, no
chart, no DNS record changes.

### 3. Wired into the k3s API server `tls-san`

The reserved floating IP is interpolated into the cloud-init
`write_files` block at `/etc/rancher/k3s/config.yaml` on the control
plane, under `tls-san`. The IP is allocated by Terraform at
resource-create time (no server dependency), so the
`templatefile()` call that renders the cloud-init resolves cleanly —
the assignment is the leaf of the dependency graph, not the
template input. The cycle-check is documented in PR #70's body.

The `tls-san` is **baked at first boot**, so changing the floating
IP value requires a control-plane recreate
(`tofu taint module.cluster.hcloud_server.control_plane[0]; tofu apply`).
This is an acceptable v1 trade-off: the floating IP is *meant* to
be stable, and if it ever changes that is a deliberate architectural
action, not a routine event.

### 4. Wired into `publish-status-address` for ingress-nginx

The platform Helm chart sets the floating IP as
`ingress-nginx.controller.extraArgs.publish-status-address` at
install/upgrade time via `--set`. ingress-nginx then writes that
address into every `Ingress.status.loadBalancer.ingress[0].ip`, and
external-dns reads from there.

This was chosen over per-Ingress
`external-dns.alpha.kubernetes.io/target` annotations because:

- Cluster-wide default scales to N Ingresses without each chart
  having to know about the floating IP.
- The floating IP is the canonical answer for every public DNS
  record this cluster serves; setting it once at the controller is
  more honest than annotating it on every consumer.
- Per-Ingress annotations are still available as an override for
  edge cases (e.g., a future Ingress that should resolve to a
  different IP).

The value is not statically knowable at chart-author time
(Terraform allocates it), so `infra/platform/values-prod.yaml`
carries `extraArgs: {}` and the maintainer supplies the value via
`--set` at every `helm install/upgrade`. The procedure is
documented in `docs/deploy.md`.

### 5. Kubeconfig server URL uses the floating IP

The `docs/deploy.md` "Fetch the kubeconfig" procedure rewrites the
server URL to `https://<ingress_floating_ip>:6443`, sourced from
`tofu -chdir=terraform/k8s/ output -raw ingress_floating_ip`. k3s
certificate rotation still requires a kubeconfig re-issue (out of
scope here), but **node replacement no longer does**.

## Consequences

### Easier

- **Kubeconfig + Cloudflare A records survive node lifecycle
  events.** Taint, recreate, k3s upgrade, hardware swap — none
  trigger a kubeconfig re-issue or DNS edit.
- **New Ingresses inherit the floating IP automatically.** No
  per-resource annotation required at chart-author time;
  external-dns picks up the controller-published address.
- **One DNS record family per public hostname.** No fan-out across
  worker nodes; one A record points at one stable IP.

### Harder

- **Floating IP changes require CP recreation.** `tls-san` is baked
  at first boot. Operationally: do not change the floating IP
  unless you are already replacing the cluster. The IP is meant to
  be stable; this is by design, not an oversight.
- **`publish-status-address` is not statically knowable.** The
  maintainer must `--set` it at every `helm install/upgrade` of the
  platform chart. Documented in `docs/deploy.md`; not automated for
  v1. A future PR may automate it (see Notes).
- **Single worker handles all public ingress.** Node failure cuts
  traffic until the floating IP is reassigned. Hetzner reassignment
  is API-driven (~10 s); a future ADR can automate it if uptime
  posture demands it. Same posture as ADR-0009 §Harder's
  "single-node v1 is a single point of failure" — the floating IP
  does not change the failure domain, only the recovery shape.

### Different

- **external-dns reads `Ingress.status.loadBalancer.ingress[0].ip`,
  not annotations.** The status field is set by ingress-nginx via
  `publish-status-address`. external-dns behaviour is unchanged;
  the *source* of the address shifts from per-Ingress annotation
  to controller-level config.
- **The cluster's "public IP" is a Terraform-managed reservable
  resource, not a property of any single server.** Same shape as
  the floating IP being a peer of `hcloud_server` in the resource
  graph rather than an attribute of one.

## Notes

Out of scope:

- **Multi-region failover, anycast IPs, CDN-fronted ingress.**
  Future ADRs if uptime / DR posture demands it.
- **IPv6 floating equivalent.** Hetzner does not reserve floating
  IPv6 addresses; servers receive a `/64` natively. AAAA records
  can be written by external-dns from the worker's native IPv6 if
  needed later. Out of scope here.
- **HA control-plane + load-balanced k3s API across multiple CPs.**
  Future ADR; the floating IP assignment would need to follow the
  active CP rather than a static index. The §2 single-line update
  shape is preserved.

### Follow-up implementation

- Implementation lands in **PR #70** (reserves the IP, wires
  `tls-san`, updates `docs/deploy.md` and the platform chart
  values).
- A future PR may automate `publish-status-address` injection — for
  example, a Helm post-renderer reading from a ConfigMap that
  Terraform writes. Deferred for v1 simplicity; the manual `--set`
  is honestly documented and the cost is one flag at deploy time.

ADR-0009 stays Accepted; this ADR is a sibling that closes a
specific tribal-knowledge gap §10 anticipated. ADR-0010 and
ADR-0011 are unaffected.
