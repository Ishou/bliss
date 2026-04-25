# Bliss Platform IaC — Terraform

This directory declares the platform-side resources Bliss owns:

- **Cloudflare Pages** project + custom domain attachments for the
  frontend static bundle (ADR-0004).
- **Fly.io** app, dedicated IPv4/IPv6, TLS cert, and machine envelope
  for the JVM API (ADR-0007).
- **Cloudflare DNS** CNAME pointing `api.wordsparrow.io` at the Fly app
  (ADR-0007 §4).

Both platforms are Terraform-managed (rather than manually clicked
through dashboards) so existence is auditable from `git log`, not from
screenshots.

Two providers are pinned in `versions.tf`: `cloudflare/cloudflare ~> 5.19`
and `andrewbaxter/fly ~> 0.1`. The Fly provider is the active community
fork of the now-archived `fly-apps/fly`; see the `versions.tf` comment
for the deviation rationale and `fly-postgres.tf` for the absent-resource
workaround.

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
export FLY_API_TOKEN=...                        # never commit; ADR-0007
tofu -chdir=terraform/ init
tofu -chdir=terraform/ apply \
  -var="cloudflare_account_id=..." \
  -var="cloudflare_zone_id=..."                 # required for the API CNAME
```

The apply creates the Pages project, the Fly app + IPs + cert, and the
`api.wordsparrow.io` CNAME. After it succeeds:

- `pages_subdomain` output names the live `*.pages.dev` URL.
- `fly_app_hostname` output names the live `*.fly.dev` URL.
- `api_url` output is the canonical `https://api.wordsparrow.io`.

Commit the generated `.terraform.lock.hcl` (it pins provider digests
and is the Terraform-equivalent of a lockfile, per CLAUDE.md
determinism rule).

The Fly Postgres cluster is **not** managed by this Terraform — the
`andrewbaxter/fly` provider has no `fly_postgres` resource. See
`fly-postgres.tf` for the bootstrap commands. The end-to-end API
maintainer checklist (Postgres bootstrap, GitHub Actions secrets,
first deploy) lands in `docs/deploy.md` with the follow-up workstream
that ships `.github/workflows/deploy-api.yml` and `grid/api/fly.toml`.

The Cloudflare API token requires extra scopes for the new DNS
record: `Zone -> DNS -> Edit` and `Zone -> Zone -> Read` on the
`wordsparrow.io` zone, in addition to the existing Pages scopes from
ADR-0004 §7.

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
API build/deploy (the workflow `.github/workflows/deploy-api.yml` plus
`grid/api/fly.toml`) ships in the follow-up workstream
`feat/infra-fly-deploy-workflow` per ADR-0007 §3. Terraform owns each
platform's existence (project / app / DNS / cert); the workflows upload
artifacts and `flyctl deploy` images.

Fly Postgres lives outside Terraform — see `fly-postgres.tf` for the
bootstrap path. Application secrets are set via `flyctl secrets set`,
not via Terraform.
