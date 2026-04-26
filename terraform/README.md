# Bliss Platform IaC — Terraform

This directory declares the platform-side resources Bliss owns:

- **Cloudflare Pages** project + custom domain attachments for the
  frontend static bundle (ADR-0004).
- **Cloudflare DNS** CNAME for `api.wordsparrow.io`. Originally pointed
  at the Fly.io app (ADR-0007); ADR-0007 was superseded by ADR-0009 and
  the Fly app was torn down. The CNAME resource is retained until
  ADR-0009 §8 step 6 cuts ownership over to in-cluster `external-dns`.

Cloudflare resources are Terraform-managed (rather than manually clicked
through dashboards) so existence is auditable from `git log`, not from
screenshots.

One provider is pinned in `versions.tf`: `cloudflare/cloudflare ~> 5.19`.

## One-time bootstrap

Prerequisites:

- A Cloudflare account (created manually; this is the platform-tenancy
  boundary called out in ADR-0004 Notes).
- A Cloudflare API token scoped per `docs/deploy.md` (Account → Cloudflare
  Pages → Edit; User → Memberships → Read; plus Zone → DNS → Edit and
  Zone → Zone → Read on `wordsparrow.io` for the API CNAME).
- The Cloudflare account ID (visible in the Cloudflare dashboard URL or
  account home page).

Then, from the repo root:

```sh
export CLOUDFLARE_API_TOKEN=...                 # never commit
tofu -chdir=terraform/ init
tofu -chdir=terraform/ apply \
  -var="cloudflare_account_id=..." \
  -var="cloudflare_zone_id=..."                 # required for the API CNAME
```

The apply creates the Pages project plus the `api.wordsparrow.io`
CNAME. After it succeeds:

- `pages_subdomain` output names the live `*.pages.dev` URL.
- `production_url` output is the canonical frontend URL.

Commit the generated `.terraform.lock.hcl` (it pins provider digests
and is the Terraform-equivalent of a lockfile, per CLAUDE.md
determinism rule).

## State

State is local for v1 (`terraform.tfstate`, gitignored). A separate ADR
will move state to a remote backend when a second Cloudflare resource
appears — at that point shared state stops being optional. Until then,
the maintainer keeps the local statefile alongside the API token and
backs both up out-of-band.

## Maintenance

Any change to the `.tf` files in this directory:

```sh
terraform -chdir=terraform/ fmt
terraform -chdir=terraform/ validate
terraform -chdir=terraform/ plan -var="cloudflare_account_id=..."
terraform -chdir=terraform/ apply -var="cloudflare_account_id=..."
```

Never edit Pages project settings in the Cloudflare dashboard — the next
`terraform apply` will revert them (CLAUDE.md "GitOps: repo is the source
of truth for system state").

## What is *not* here

Frontend build/deploy lives in `.github/workflows/deploy-frontend.yml`.
API build/deploy moved to the self-managed k3s cluster per ADR-0009 —
see `terraform/k8s/` and `docs/deploy.md`. Terraform here owns the
Cloudflare-side existence (Pages project + DNS); the application
deploy workflow is owned by the k8s subtree.

## Self-managed k8s (in progress)

`terraform/k8s/` holds the **provider-agnostic skeleton** for a
self-managed k3s cluster — the successor to the Fly.io tier per
ADR-0009 (which supersedes ADR-0007). The skeleton currently declares
only the variable/output contract; the Hetzner implementation lands
in a follow-up PR. See [`terraform/k8s/README.md`](k8s/README.md) for
the provider-swap design.

The k8s module has its own remote backend — Hetzner Object Storage,
FSN1, per ADR-0010 — see
[`terraform/k8s/README.md`](k8s/README.md#remote-state) for the
backend block and bootstrap pointer. This root still keeps state local
pending its own remote-state migration.

The Cloudflare API token used by in-cluster operators (external-dns,
cert-manager DNS-01) is **not** an input to the k8s module — it belongs
to the future Helm/Flux bootstrap layer, which owns its own credential
plumbing. The cluster-provisioning contract is intentionally decoupled
from any specific in-cluster operator.
