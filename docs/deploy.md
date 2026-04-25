# Deployment

How the Bliss frontend reaches production. Authoritative spec is
[ADR-0004](./adr/0004-hello-world-deployment.md); this file documents the
operational binding required by ADR-0004 §7.

## Pipeline

`.github/workflows/deploy-frontend.yml` builds `frontend/` on push to `main`
(production) or on any PR (preview) and uploads the static bundle to
Cloudflare Pages via Direct Upload. Cloudflare itself does not clone the
repo.

The Pages project is declared as Terraform in `terraform/`; see
`terraform/README.md` for the bootstrap procedure.

## Secrets bound by the workflow

Per ADR-0004 §7, the *names and bindings* of secrets live in repo code;
only the *values* are injected at runtime via GitHub Actions Secrets.

| Secret | Bound at | What it is | Secret? |
|---|---|---|---|
| `CLOUDFLARE_API_TOKEN` | `cloudflare/pages-action` step `apiToken` | Cloudflare API token scoped to the Pages project. | Yes |
| `CLOUDFLARE_ACCOUNT_ID` | `cloudflare/pages-action` step `accountId` | Cloudflare account UUID. Not secret in itself; stored alongside the token for convenience. | No (but treated as such for symmetry) |
| `GITHUB_TOKEN` | `cloudflare/pages-action` step `gitHubToken` | Auto-issued by GitHub Actions; lets the action comment the preview URL on the PR. | Managed |

## Required Cloudflare API token scopes

When creating the token in the Cloudflare dashboard
(My Profile -> API Tokens -> Create Token, *Custom Token*), grant the
minimum needed for Direct Upload:

- **Account -> Cloudflare Pages -> Edit** (create deployments, list
  projects).
- **User -> Memberships -> Read** (the action verifies token ownership at
  startup).

Restrict the token's *Account Resources* to the single Cloudflare account
that owns the Pages project. Set an expiry; rotate before it lapses.

Verify the live scope list against Cloudflare's docs at token creation
time — Cloudflare occasionally renames scope groups.

## Pre-deploy maintainer checklist (one-time)

Done once after this PR merges, in this order:

1. Create the Cloudflare API token with the scopes above. Copy the value;
   it is shown once.
2. Bootstrap the Pages project via Terraform:
   ```sh
   export CLOUDFLARE_API_TOKEN=<token from step 1>
   terraform -chdir=terraform/ init
   terraform -chdir=terraform/ apply -var="cloudflare_account_id=<account uuid>"
   ```
   Commit the generated `.terraform.lock.hcl`.
3. Add the same `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` as
   *GitHub Actions Secrets* (Repo Settings -> Secrets and variables ->
   Actions -> *New repository secret*).

After step 3, the next push to `main` deploys to production.

## Rollback

Per ADR-0004 §5:

- **Primary:** revert the offending commit on `main` via PR + squash-merge.
  The deploy workflow re-runs and pushes the prior bundle. GitOps-pure:
  repo state matches live state.
- **Escape hatch:** Cloudflare Pages dashboard -> *Deployments* -> select
  a prior deployment -> *Rollback*. Use only when reverting in git is
  blocked (e.g. broken build, dependency yanked). Introduces drift; open
  a follow-up PR to make the repo match.

## Deploy provenance

Every deployment is traceable from `git log`:

- Conventional-commit message identifies the workstream.
- Branch name (per `branch-name.yml`) identifies the type.
- The deploy workflow attaches the GitHub run URL to the Pages
  deployment, visible in the Cloudflare dashboard.

This satisfies ADR-0001 §9 (fleet observability) for the deploy boundary.
