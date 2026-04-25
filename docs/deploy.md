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

## Custom domain (`wordsparrow.io`)

Per ADR-0005 §1, the production domain is `wordsparrow.io`. The
attachment is IaC (`terraform/cloudflare-pages-domain.tf`) — the
maintainer's only manual step is DNS at the registrar (or at Cloudflare
DNS, if the zone has been transferred there).

**One-time after the next `tofu apply`:**

1. Apply the Terraform with the custom domain enabled (it is enabled
   by default; `var.custom_domain` defaults to `wordsparrow.io`):
   ```sh
   tofu -chdir=terraform/ apply -var="cloudflare_account_id=<account uuid>"
   ```
   Two new resources are created: `cloudflare_pages_domain.apex` for
   the apex and `cloudflare_pages_domain.www[0]` for the `www.` alias.
   Cloudflare returns a *Pending verification* status until DNS is set.

2. Configure DNS so Cloudflare can verify ownership and serve traffic.
   Two paths:

   **(a) Zone managed by Cloudflare DNS (recommended).** Add the zone
   in the Cloudflare dashboard, point the registrar at the issued
   nameservers, then add two records (both proxied / orange-cloud):
   - `wordsparrow.io` — `CNAME` flattening to `<project>.pages.dev`
     (Cloudflare resolves the apex CNAME automatically).
   - `www.wordsparrow.io` — `CNAME` to `<project>.pages.dev`.

   **(b) Zone managed externally.** Most registrars do not support a
   true apex CNAME. Two options:
   - If the registrar offers `ALIAS` / `ANAME`, point
     `wordsparrow.io` to `<project>.pages.dev`.
   - Otherwise, use `A` records to Cloudflare Pages's anycast IPs (see
     `https://developers.cloudflare.com/pages/configuration/custom-domains/`
     for the current list — it changes; verify before pasting).
   For `www.wordsparrow.io`, a `CNAME` to `<project>.pages.dev` works
   on every registrar.

3. Wait for verification. Cloudflare's dashboard
   (Pages -> Project -> Custom domains) flips each domain from
   *Pending* to *Active* within a few minutes once DNS resolves.
   SSL certificates are issued automatically (Cloudflare handles ACME);
   no certbot, no renewal cron.

4. (Optional) Canonicalize on the apex via a `_redirects` rule in
   `frontend/public/_redirects`:
   ```
   https://www.wordsparrow.io/* https://wordsparrow.io/:splat 301!
   ```
   Ships in a follow-up frontend workstream alongside any other
   redirect rules.

**To temporarily skip custom-domain attachment** (e.g. before the
domain is registered, or in a fork): pass
`-var="custom_domain="` to `tofu apply`. Both `cloudflare_pages_domain`
resources skip via the `count` guard and the deployment stays on the
`*.pages.dev` subdomain.

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
