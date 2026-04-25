# Bliss Platform IaC — Terraform

This directory declares the platform-side resources Bliss owns. Today that is
exactly one resource: the Cloudflare Pages project that hosts the frontend
static bundle.

The Pages project is Terraform-managed (rather than manually clicked through
the dashboard) per ADR-0004 finding 1 fix on PR #10 — its existence must be
auditable from `git log`, not from a screenshot.

## One-time bootstrap

Prerequisites:

- A Cloudflare account (created manually; this is the platform-tenancy
  boundary called out in ADR-0004 Notes).
- A Cloudflare API token scoped per `docs/deploy.md` (Account → Cloudflare
  Pages → Edit; User → Memberships → Read).
- The Cloudflare account ID (visible in the Cloudflare dashboard URL or
  account home page).

Then, from the repo root:

```sh
export CLOUDFLARE_API_TOKEN=...                 # never commit
terraform -chdir=terraform/ init
terraform -chdir=terraform/ apply -var="cloudflare_account_id=..."
```

The apply creates the Pages project. After it succeeds, the
`pages_subdomain` output names the live `*.pages.dev` URL. Commit the
generated `.terraform.lock.hcl` (it pins provider digests and is the
Terraform-equivalent of a lockfile, per CLAUDE.md determinism rule).

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
Terraform owns the project's existence; the workflow uploads artifacts
(Direct Upload model — ADR-0004 §3).
