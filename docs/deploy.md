# Deployment

How Bliss reaches production. The frontend (static bundle) ships to
Cloudflare Pages per [ADR-0004](./adr/0004-hello-world-deployment.md);
the JVM API ships to Fly.io per
[ADR-0007](./adr/0007-jvm-fly-deployment.md). This file documents the
operational binding required by ADR-0004 §7 and ADR-0007 §3 / §8 —
frontend first, then API.

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

# API deployment (Fly.io)

How the Bliss JVM API reaches production. Authoritative spec is
[ADR-0007](./adr/0007-jvm-fly-deployment.md); this section is the
operational binding required by ADR-0007 §3 and §8.

## Architecture

```
                              ┌────────────────────────────────────┐
   wordsparrow.io  ───────────│  Cloudflare Pages (static bundle)  │
                              └────────────────────────────────────┘
                                          │ HTTPS (XHR / SSE)
                                          ▼
                              ┌────────────────────────────────────┐
   api.wordsparrow.io  ───────│  Fly.io (Kotlin/Ktor JVM)          │
                              │  + Fly Postgres (.flycast private) │
                              └────────────────────────────────────┘
```

The frontend stays at `wordsparrow.io` on Cloudflare Pages. The API at
`api.wordsparrow.io` lives on Fly with a dedicated IPv4/IPv6, a
Fly-issued Let's Encrypt cert, and a Cloudflare CNAME (DNS-only, no
proxy) pointing at `wordsparrow-api.fly.dev`. Cloudflare never sees the
API path; only Fly does.

## Pipeline

`.github/workflows/deploy-api.yml` builds `grid/api/` on push to `main`
(production deploy) or on any matching PR (build-only — no deploy). The
shadowJar lands at `grid/api/build/libs/grid-api-<version>-all.jar`,
gets baked into the Docker image declared by `grid/api/Dockerfile`, and
is uploaded by `flyctl deploy --remote-only`. The GitHub runner never
hosts the build context: Fly's remote builder does.

Per ADR-0007 §5, **PRs do not deploy a Fly preview**. Frontend previews
use Mock Service Worker (MSW) handlers driven by the OpenAPI spec; the
real API runs only on `main`. A PR build that touches the API still
runs the full shadowJar to catch compile/test breakage early — it just
stops short of `flyctl deploy`.

## Secrets bound by the workflow

Per ADR-0007 §8, the *names and bindings* of secrets live in repo code;
only the *values* are injected at runtime via GitHub Actions Secrets.

| Secret | Bound at | What it is | Secret? |
|---|---|---|---|
| `FLY_IO_TOKEN` | `flyctl deploy` step env `FLY_API_TOKEN` | Fly.io organisation access token. Stored under the GitHub-secret name `FLY_IO_TOKEN` (maintainer's chosen convention); re-exported as `FLY_API_TOKEN` because that is the variable `flyctl` itself reads. The two-name dance is intentional. | Yes |
| `FLY_API_TOKEN` (local env, not a GitHub secret) | `tofu apply` provider auth | Same Fly token, exported under the name `andrewbaxter/fly` Terraform provider expects when the maintainer runs `tofu apply` from their machine. | Yes |
| `GITHUB_TOKEN` | (auto, runtime) | Auto-issued by GitHub Actions; lets the workflow comment build status on a PR. | Managed |

## Required Fly token scopes

Create the deploy token at the **organisation** level (so a future
multi-app split — staging, preview-real-API — does not require minting
new tokens). On the Fly dashboard:

- Personal/org settings → Access Tokens → *Create access token*.
- Scope: **Deploy** (or whatever the current Fly UI calls deploy-only
  permission — Fly has renamed this twice).
- Set an expiry; rotate before it lapses.

Verify the live scope list against Fly's docs at
<https://fly.io/docs/security/tokens/> at token-creation time — Fly has
shipped multiple token-scoping iterations since 2023; the dashboard
labels lag the docs.

## Maintainer one-time post-merge checklist

Done once after this PR (`feat/infra-fly-deploy-workflow`) merges, in
this order. Steps that overlap with the PR #33 (`feat/infra-fly-app`)
checklist are noted; skip those if already done.

1. **(From PR #33, if not already)** Expand the Cloudflare API token to
   cover the new DNS record: add **Zone → DNS → Edit** and **Zone →
   Zone → Read** on the `wordsparrow.io` zone, in addition to the Pages
   scopes from ADR-0004 §7.

2. **(From PR #33, if not already)** Look up the Cloudflare zone ID for
   `wordsparrow.io` (Cloudflare dashboard → zone overview, right-hand
   sidebar) and pass it as `-var="cloudflare_zone_id=..."` to
   `tofu apply`, or persist it in a local (gitignored) tfvars file.

3. **Bootstrap the Fly Postgres cluster** (the `andrewbaxter/fly`
   provider has no `fly_postgres` resource; see
   `terraform/fly-postgres.tf` for the deviation note):
   ```sh
   flyctl postgres create \
     --name wordsparrow-api-db \
     --region cdg \
     --vm-size shared-cpu-1x \
     --volume-size 1 \
     --initial-cluster-size 1 \
     --org <your-fly-org>
   ```

4. **Attach Postgres to the API app** so `DATABASE_URL` is auto-injected
   into the API's secret store (over the `.flycast` private network, no
   public IP):
   ```sh
   flyctl postgres attach wordsparrow-api-db --app wordsparrow-api
   ```

5. **Apply the platform Terraform** to create the Fly app, the
   dedicated IPv4/IPv6, the TLS cert, and the
   `api.wordsparrow.io` CNAME:
   ```sh
   export CLOUDFLARE_API_TOKEN=...
   export FLY_API_TOKEN=...                     # same token used in step 6
   tofu -chdir=terraform/ init
   tofu -chdir=terraform/ apply \
     -var="cloudflare_account_id=..." \
     -var="cloudflare_zone_id=..."
   ```

6. **Confirm the GitHub Actions secret** `FLY_IO_TOKEN` is set on the
   `Ishou/bliss` repo (Repo Settings → Secrets and variables → Actions).
   The maintainer set this up in advance per the workstream notes; this
   step is verification only.

7. **Trigger the first deploy** by pushing any commit that touches a
   `paths:` filter entry to `main` — for an immediate deploy without
   pending changes, use the GitHub Actions UI's *Re-run all jobs* on the
   most recent `Deploy API` run after Postgres is attached.

After step 7 succeeds, `https://api.wordsparrow.io/v1/health` returns
`{"status": "ok", ...}`.

## Rollback

Per ADR-0007 §6:

- **Primary:** revert the offending commit on `main` via PR + merge.
  The deploy workflow re-runs and `flyctl deploy` pushes the prior JAR.
  GitOps-pure: repo state matches live state.
- **Escape hatch:** `flyctl releases list --app wordsparrow-api` then
  `flyctl releases rollback <id> --app wordsparrow-api` from the
  maintainer's machine. Use only when reverting in git is blocked
  (e.g. broken build, dependency yanked). Introduces drift; open a
  follow-up PR to make the repo match.

Database migrations are backward-compatible per ADR-0007 §6
(expand-and-contract) — a JAR rollback never requires a schema
rollback. The migration tooling itself is decided in the persistence
workstream's ADR.

## Promotion strategy reminder

The API runs **only on `main`**. Per ADR-0007 §5, there is no per-PR
Fly app — the Fly free tier is gone, per-PR cost (~$2-5/PR) and per-PR
cleanup automation aren't worth the review value, and contract tests
(ADR-0003 §7, §9) plus MSW-mocked frontend previews close the
review-feedback gap. If end-to-end testing on real data becomes a
bottleneck, the trigger is a real staging environment, not per-PR Fly
apps.

## Where to look when it breaks

- **Build / deploy failures:** GitHub Actions run page for the failed
  `Deploy API` workflow run. Logs include `gradlew shadowJar` output
  and `flyctl deploy --verbose` output.
- **Runtime errors / crash loops:** `flyctl logs --app wordsparrow-api`
  for the live tail; Fly's web dashboard for historical logs.
- **DNS / certificate issues:** Cloudflare dashboard (zone DNS tab)
  for the CNAME state, plus `flyctl certs show api.wordsparrow.io` for
  the Fly-side cert state. Both must be healthy for the custom domain
  to resolve end-to-end.
- **Postgres issues:** `flyctl status --app wordsparrow-api-db` and
  `flyctl logs --app wordsparrow-api-db`. The DB is a separate Fly
  app from the API.

