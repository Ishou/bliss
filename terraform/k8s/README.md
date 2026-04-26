# Bliss k8s Cluster Module — Provider-Agnostic Skeleton

This directory defines the **interface** for a self-managed k3s cluster:
the input variables, outputs, and contract that any cloud-provider
implementation must satisfy. There are no cloud resources here yet — the
first concrete implementation (Hetzner) lands in a follow-up PR. See
[ADR-0009](../../docs/adr/0009-self-managed-k8s-deployment.md) for the rationale.

## Provider-swap pattern

The module is split into:

- **`variables.tf` / `outputs.tf` / `main.tf`** — the contract. Every
  provider implementation must accept the same variables and produce the
  same outputs (with the same sensitivity flags).
- **`providers/<name>/`** — self-contained implementation modules. Each
  one is invoked from the root by changing a single `source = ` line. Hetzner
  ships first; Scaleway and OVH are documented swap targets so the
  vendor-lock surface is visibly small.

Why a directory swap rather than a `count`-gated mega-module: each
provider's resource graph is genuinely different (Hetzner has placement
groups, Scaleway has security groups, OVH has its own networking model).
Conditional resources hide that complexity behind broken abstractions.
A clean per-provider module keeps each implementation legible.

## Planned layout

```
terraform/k8s/
  versions.tf        ← Terraform CLI pin only; no required_providers (yet)
  variables.tf       ← contract inputs
  outputs.tf         ← contract outputs
  main.tf            ← contract documentation, no resources
  README.md          ← this file
  providers/
    hetzner/         ← first implementation (PR3)
    scaleway/        ← future swap target
    ovh/             ← future swap target
```

The `providers/` subdirectories are intentionally absent at this stage —
adding empty placeholder dirs would either churn `git` (`.gitkeep`) or
mislead `terraform init` into discovering nothing. The first one lands
with its full implementation in PR3.

## Why no `required_providers` block here

`versions.tf` pins the Terraform CLI but deliberately omits a
`required_providers` block. Terraform resolves provider requirements by
walking the module graph; declaring `hetznercloud/hcloud` (or any other)
at this layer would force every consumer of the contract to install that
provider even when they're swapping in a different one. Provider
requirements live inside each `providers/<name>/versions.tf`.

## `node_size` is provider-coupled by design

The `node_size` variable is a free-form string (e.g. `cx22` for Hetzner,
`DEV1-S` for Scaleway, `b2-7` for OVH). Normalising it to an abstract
t-shirt enum (`small`/`medium`/`large`) was rejected: it hides the actual
cost/perf knobs and breaks down the moment a provider lacks an obvious
peer for a tier. Documented coupling beats leaky abstraction.

## What this module does NOT do

Bootstrapping in-cluster operators (cert-manager, external-dns,
ingress-nginx, CloudNative-PG, observability stack) is **out of scope**
and handled by a separate Helm/Flux layer in a later PR. The boundary is
deliberate: this module stops the moment it produces a kubeconfig-ready
cluster. Mixing infra provisioning and workload bootstrap inside one
Terraform run conflates failure domains and stretches `apply` cycles.

A direct consequence: the Cloudflare API token used by the in-cluster
operators (external-dns, cert-manager DNS-01) is **not** an input to
this module. That credential is owned by the Helm/Flux bootstrap layer
and injected there, so the cluster-provisioning contract stays decoupled
from any specific in-cluster operator's credential requirements.

## Remote state

State for this root lives in **Hetzner Object Storage**, region
**FSN1**, bucket **`bliss-tf-state`**, under the key
`terraform/k8s/terraform.tfstate`. Native S3 locking
(`use_lockfile = true`) is enabled; no DynamoDB-equivalent is needed.
See [ADR-0010](../../docs/adr/0010-terraform-remote-state-hetzner.md)
for the rationale (jurisdiction, locking semantics, and the
time-limited `skip_s3_checksum` workaround for OpenTofu issue #2605).

**First-time bootstrap**: the state bucket itself is provisioned
out-of-band as a one-time human step before the first `terraform init`
against this root. The recipe lives in the PR body that wired the
backend (see `git log -- versions.tf`); it will be absorbed into
`docs/deploy.md` by the cluster bring-up PR.

## Cross-references

- [ADR-0009](../../docs/adr/0009-self-managed-k8s-deployment.md) — Self-managed k8s,
  default provider Hetzner, provider-swappable.
- Root [`terraform/README.md`](../README.md) — current Fly.io + Cloudflare
  Pages deployment. Fly resources stay in place until the cutover PR
  flips DNS to the new cluster.
