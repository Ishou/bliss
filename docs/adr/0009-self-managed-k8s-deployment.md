# ADR-0009: Self-managed Kubernetes Deployment (Hetzner default, provider-swappable)

## Status

Proposed. Partially supersedes ADR-0007 (the API and Postgres move off
Fly.io; the frontend stays on Cloudflare Pages, ADR-0004 unchanged).
ADR-0007 will be amended in the cutover PR after migration is validated;
this ADR documents the decision, not the rollout.

## Context

ADR-0007 placed the Kotlin/Ktor API on Fly.io with Fly Postgres co-located
in the same app, and committed to `andrewbaxter/fly` Terraform plus
`flyctl deploy` from GitHub Actions. That choice was honest at the time
but has accumulated three concrete problems that the manifesto does not
let us paper over:

1. **Fly Postgres has no Terraform resource.** The `andrewbaxter/fly`
   provider exposes apps, machines, IPs, and certs, but not the managed
   Postgres cluster — bootstrap requires a manual `flyctl postgres
   create`. ADR-0007 acknowledged this in §2 ("`fly_postgres` absent —
   bootstrapped in `terraform/fly-postgres.tf`") and pushed forward
   anyway. The manifesto's "all infrastructure defined in code, in the
   repo. No manual infra changes" rule does not bend to "the provider
   doesn't support it"; that is exactly the case the rule is written
   for.

2. **Architecture-level vendor lock-in.** Fly's app primitives
   (`fly.toml`, machines, `.flycast` private network, Fly Postgres) are
   not portable. The maintainer intends to deploy further projects on
   the same operational substrate and is unwilling to re-pick a cloud
   per project, or to be tied to one cloud's product roadmap and
   pricing. ADR-0007 §Consequences flagged Cloudflare lock-in as
   bounded; Fly lock-in is not — the Fly-shaped pieces are the
   deployment, not skin on top of it.

3. **Local↔prod parity gap.** Fly's Firecracker VMs are not
   reproducible on a developer machine. `fly.toml` plus a Dockerfile is
   not the same contract as what runs in production. The manifesto's
   "dev/prod parity: same DB engine, same runtime, same container
   images locally and in production" rule is satisfied at the image
   layer but not at the orchestration layer; we want the orchestration
   layer to match too.

The goal therefore is a **portable contract** — vanilla Kubernetes —
where the cloud provider is a swappable Terraform module rather than an
architectural commitment. Per-PR Fly preview deploys were already banned
in ADR-0007 §5 (frontend-only previews via spec-driven MSW mocks); that
posture is preserved here for the same reasons (cost, cleanup, and the
schema-first contract gates already cover what previews would).

## Decision

### 1. Deployment target: self-managed k3s on commodity Linux VMs

The API and Postgres run on a **self-managed k3s cluster** on commodity
Linux VMs. k3s (not full upstream k8s) because the cluster footprint is
v1-small, etcd-on-the-control-plane is acceptable at this scale, and
the single-binary install keeps node bring-up scriptable.

The cluster contract is plain Kubernetes: standard `Deployment`,
`Service`, `Ingress`, `PersistentVolumeClaim`, plus standard CRDs from
the operators in §3. Nothing in app manifests references the cloud
provider or k3s specifically.

### 2. Default cloud provider: Hetzner Cloud

**Hetzner Cloud** is the default cloud, picked on cost and DX:

- **Cost.** A CX22 instance (2 vCPU / 4 GB / 40 GB SSD) is ~€4/month
  in the Falkenstein/Helsinki regions and similar in Nuremberg.
  Comparable specs at OVH and Scaleway run 2–3× higher. For the v1
  footprint (1–2 nodes + load balancer + a small block volume),
  expect €10–15/month all-in — comparable to or slightly cheaper
  than Fly + Fly Postgres at the same scale, and the marginal cost
  of the second project on the same cluster is near zero.
- **Mature Terraform provider.** `hetznercloud/hcloud` covers
  servers, networks, load balancers, volumes, firewalls, and SSH
  keys cleanly. No `flyctl postgres create`-shaped gaps.
- **Clean DX and EU jurisdiction.** GDPR-native, low-latency to the
  French audience (Falkenstein and Nuremberg both ~30 ms from
  Paris), no surprise egress pricing.

**Provider abstraction.** The Terraform layout treats the cloud as one
implementation of a provider-agnostic interface: a "cloud-resources"
module (nodes, network, load balancer, block storage) and a "cluster
contract" module (k3s install, kubeconfig, operators). The Hetzner
module is the only implementation written in this workstream;
**Scaleway and OVH are documented swap targets**. Estimated effort to
swap: 1–2 days for a sibling cloud-resources module, **zero** for the
cluster contract or any app manifest.

### 3. Cluster operators (in-cluster, provider-agnostic)

The following operators run in-cluster and are installed via Helm with
values committed to the repo:

- **cert-manager** — TLS certificate issuance and renewal via
  Let's Encrypt ACME. Replaces Fly's auto-managed certs.
- **external-dns** — reconciles `Ingress` host rules into Cloudflare
  DNS records using the Cloudflare provider. **DNS stays on
  Cloudflare regardless of cloud choice** — the registrar / zone
  decision is orthogonal to where compute runs. Replaces the
  hand-managed `cloudflare_dns_record` Terraform resource from
  ADR-0007.
- **ingress-nginx** — standard ingress controller. Plain
  Kubernetes-native ingress; nothing Hetzner-specific.
- **CloudNativePG (CNPG)** — Postgres operator. **Replaces Fly
  Postgres**, runs in the same cluster, exposes a `Cluster` CRD that
  is a regular Kubernetes object — fully Terraform/GitOps-managed,
  closing the §1.1 gap. Backups go to S3-compatible object storage.
  **Interim v1 destination: Backblaze B2** (cheapest sensible default,
  S3-compatible, orthogonal to the compute cloud). If EU-jurisdiction
  becomes a hard constraint before Hetzner Object Storage GAs, swap
  to Scaleway Object Storage. The durable choice (and migration off
  the interim) is owned by the operator-config workstream.

All four are upstream, vendor-neutral, and run identically on any
conformant Kubernetes cluster.

### 4. App deployment: Helm chart + GitHub Actions

The WordSparrow API ships as a **Helm chart** in the repo (likely under
`grid/api/deploy/chart/` — exact path is an implementation detail).
The first implementation PR pins this path so subsequent ADRs and
runbooks can reference it unambiguously. CD is GitHub Actions running
`helm upgrade --install` against the cluster's kubeconfig, which is
injected as a GitHub Actions secret (scoped to the deploy service
account, not cluster-admin).

No `flyctl`. No `fly.toml`. The deploy workflow's only platform
dependency is `kubectl` + `helm` against a kubeconfig — identical
locally and in CI.

**Push-based CD: accepted exception to the GitOps reconciliation-loop
rule.** The manifesto's Infrastructure section requires a reconciliation
loop that detects and alerts on drift between declared and actual state.
`helm upgrade --install` from GitHub Actions converges state at deploy
time but does not continuously reconcile; if a resource is hand-edited
on the cluster after a deploy, nothing detects it. This is a real gap,
not a wording quibble. We accept it at v1 because the cost of running
ArgoCD or Flux on a single CX22 node (extra controller, extra Helm
release to operate, ~150–300 MB RAM) outweighs its value at our v1
scale (one app, one cluster, single operator). **Revisit trigger**: when
the cluster grows beyond two nodes, when a second app joins the cluster,
or after the first observed drift incident — whichever comes first —
open an ADR to introduce Flux or ArgoCD as the reconciliation
controller. Until then, the discipline is "no kubectl edits in prod;
all changes go through the repo."

### 5. Local↔prod parity via k3d

**k3d** (k3s-in-Docker) runs the same cluster contract on a developer
machine. The same Helm charts install the same operators with the same
values; `kubectl` commands and manifests are identical. A developer
reproducing a production issue locally hits the same operator versions,
the same CNPG `Cluster` CRD, the same ingress controller, the same
cert-manager (with a self-signed `ClusterIssuer` swapped in for
ACME). The §1.3 parity gap closes at the orchestration layer, not just
the container-image layer.

### 6. Frontend stays on Cloudflare Pages

The frontend continues to deploy to Cloudflare Pages via the existing
workflow. ADR-0004 is unchanged. Moving a static SPA into the cluster
buys nothing (worse edge presence, more moving parts) and costs the
free unlimited-bandwidth Pages tier. The two-platform shape from
ADR-0007 §4 persists; only the back half changes.

### 7. Per-PR previews stay frontend-only

ADR-0007 §5's rule — frontend previews via spec-driven MSW mocks, no
per-PR backend deploys — is **preserved**. Spinning up a per-PR API +
Postgres in the cluster would explode the cluster footprint
(N transient namespaces, N CNPG `Cluster`s, N cert-manager
certificates) for the same review value the MSW mocks already provide
through the schema-first contract gates (ADR-0003 §7, §9). When real
end-to-end staging becomes a bottleneck, the trigger is a staging
ADR — same posture as ADR-0007 §5 noted.

### 8. Migration path (high level)

The migration is sequenced as: Terraform skeleton with the
provider-agnostic interface → Hetzner cloud-resources module → cluster
+ operators → WordSparrow Helm chart → CD workflow → DNS cutover →
Fly teardown (and ADR-0007 amendment). Each step is its own
under-400-line PR; the dispatch plan with PR numbers lives in the
working session, not in this ADR.

### 9. Rollback strategy

Per the manifesto's "Rollback is always one click" rule, carrying
forward ADR-0007 §6 adapted to Helm:

- **Primary mechanism**: revert the offending commit on `main` via PR.
  CD re-runs `helm upgrade --install` and pushes the prior chart
  revision and image. GitOps-pure; repo state matches cluster state.
- **Escape hatch**: `helm rollback <release> <revision>` (e.g.
  `helm rollback wordsparrow-api 42`) from the maintainer's machine
  against the cluster kubeconfig. **This introduces drift between repo
  state and cluster state — exactly the same shape as ADR-0007 §6's
  `flyctl releases rollback` escape hatch — and a follow-up PR is
  required to reconcile.** This is also the failure mode the §4
  push-based-CD exception makes harder to detect: a `helm rollback`
  leaves no automated alarm. Use only when a commit-revert is blocked
  (e.g., CD itself is broken).
- **Database migrations** are **backward-compatible per the manifesto**
  (expand-and-contract). A failed deploy can roll back the chart and
  image without rolling back the schema; the prior image must still
  understand the newer schema. This rule is binding from the first
  migration. Migration tooling is unchanged from ADR-0007 §6 — its
  own ADR with the persistence workstream.

### 10. What this ADR does not decide

- Specific operator versions and Helm values (operator-config
  workstream).
- Backup destination once Hetzner Object Storage GAs — the §3 interim
  (Backblaze B2) is the v1 starting point; the durable choice and any
  migration off it belong to the operator-config workstream.
- Multi-node HA, control-plane HA, multi-region, or DR posture —
  v1 runs single control-plane on one node with the workload on a
  second. HA is a future ADR if uptime targets demand it.
- Cluster-internal observability stack (Prometheus / Grafana / Loki
  vs. hosted) — own ADR with the broader observability work
  ADR-0007 §7 already opened. **App-level OTel (ADR-0007 §7) is
  unchanged by this migration**: spans, RED metrics, and structured
  JSON logs still ship with the API implementation workstream; only
  the host platform changes.
- Secrets-in-cluster mechanism (sealed-secrets vs. SOPS vs.
  external-secrets) — own ADR with the operator-config workstream.
  **Interim bootstrap**: at cluster bring-up, secrets (CNPG Postgres
  superuser password, future Anthropic / Stripe / OAuth keys) are
  created by `kubectl create secret` against the freshly-provisioned
  cluster, with the exact commands and required values documented in
  `docs/deploy.md` (added by the cluster bring-up PR). Values are
  never committed; the file documents *what* and *why*, not the
  values themselves. This is explicitly the same shape as ADR-0007's
  `flyctl secrets set` — a one-time human step, but documented in the
  repo so it does not become the next `flyctl postgres create`-style
  tribal-knowledge gap. The interim retires when the secrets ADR
  lands.
- Cost monitoring / budget alerts.

## Consequences

### Easier

- **Portability.** Swapping clouds is a 1–2 day exercise on the
  cloud-resources module. App manifests, operators, and the cluster
  contract do not change. The architecture-level lock-in from §1.2
  retires.
- **Full IaC.** CNPG `Cluster` is a regular Kubernetes CRD, applied
  by the same `helm upgrade` step as everything else. No
  `flyctl postgres create` carve-out. The §1.1 manifesto violation
  retires.
- **Local↔prod parity at the orchestration layer.** k3d locally and
  k3s in prod run the same operators, same manifests, same CRDs.
  §1.3 retires.
- **Multi-project reuse.** The next project drops in as another Helm
  release in the same cluster, or as another identical cluster from
  the same Terraform modules. Per-project deploy-target ADRs become
  a one-paragraph "uses the standard cluster" reference.

### Harder

- **We now own cluster operations.** k3s upgrades, node OS patching,
  etcd snapshots and restore drills, operator upgrades (cert-manager,
  CNPG, ingress-nginx, external-dns), Postgres major-version
  upgrades. Honest accounting: ~1–2 hours/month of attention vs.
  Fly's near-zero, traded for the portability above. A runbook ships
  in a follow-up workstream.
- **Bootstrap is more code.** Two Terraform modules + Helm charts +
  a kubeconfig flow vs. a `fly.toml` plus a deploy workflow. The
  one-time cost is real; it amortizes across every future project.
- **Single-node v1 is a single point of failure.** Same posture as
  ADR-0007's single-machine + single-region Fly Postgres, but now
  the failure domain includes the k3s control plane. HA is a future
  ADR; until then, RPO/RTO are bounded by etcd snapshot cadence
  and CNPG backup cadence (both set in the operator-config
  workstream).

### Different

- **Cost shape.** Per-app Fly billing becomes a flat node bill —
  ~€10–15/month for the v1 footprint. Comparable to or slightly
  cheaper than Fly + Fly Postgres at v1 scale; materially better
  economics from project two onward.
- **Mental model.** "Where does the API run?" becomes "in the
  cluster", same as every future service. The two-platform front/back
  split from ADR-0007 §4 stays, but the back half is now generic
  Kubernetes rather than a Fly-specific shape.
- **DNS becomes operator-managed.** `external-dns` reconciles records
  from `Ingress` annotations rather than a hand-written
  `cloudflare_dns_record` resource. Cloudflare stays the zone owner.

## Notes

This ADR is revisited if any of the following occur:

- Cluster ops time exceeds ~4 hours/month at v1 footprint — revisit
  whether a managed k8s offering (Hetzner's managed k8s when GA, or
  a sibling cloud's) is worth the cost and the partial re-introduction
  of provider primitives.
- Hetzner materially changes pricing, regional availability, or the
  Terraform provider — fall back to the documented Scaleway/OVH swap.
- A real outage shows the single-node + single-region posture is
  insufficient — HA / DR ADR.
- Per-PR backend previews become genuinely necessary (e.g., a feature
  the MSW mocks cannot represent) — staging-tier ADR, not a return
  to per-PR Fly apps.

ADR-0007 stays Accepted until the cutover PR amends it. This ADR is
the unblocking record for a multi-PR migration workstream; **no
infrastructure changes ship in this PR.**
