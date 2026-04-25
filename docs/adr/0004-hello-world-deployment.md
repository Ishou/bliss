# ADR-0004: Hello-World Deployment

## Status

Accepted

## Context

The project has accumulated three architectural ADRs and a frontend scaffold
(PR #7) but has never been deployed. Per `MANIFESTO.md`, "CI is the only path
to production" and "Rollback is always one click" — both of which require an
actual production target to exist. Per ADR-0001 §7, deployment-target choices
require an ADR merged before any implementation.

This ADR is intentionally scoped to the *hello-world* deployment: prove the
pipeline end-to-end with the smallest credible target. Real product surfaces
(JVM service, OpenAPI-driven HTTP layer, persistent storage, multiplayer)
will each get their own deployment ADR when they are introduced.

What exists today and is therefore deployable:

- The `frontend/` bounded context (PR #7) — Vite + React + Panda CSS,
  produces a static bundle (`pnpm build`) of ~95 KB gzipped. Renders one
  route at `/` with the heading "Bliss".
- The `grid/` JVM context — domain and infrastructure layers only. There is
  **no HTTP entry point**, no `application/`, no `api/`. Adding one is
  itself a new bounded-context-layer workstream.

The selection space considered for v1:

- **What gets deployed first:** the frontend static bundle, a new JVM
  hello-world service, or both.
- **Where:** Cloudflare Pages, Vercel, Netlify, GitHub Pages, Render,
  Fly.io, Railway, AWS App Runner, GCP Cloud Run, self-hosted.
- **How:** GitHub Actions driving the platform's deploy mechanism, IaC
  parameters in repo, container or buildpack-based artifacts.

## Decision

### 1. What is deployed first: the frontend static bundle

Only the `frontend/` static bundle is deployed in v1. The JVM service is
*not* deployed yet.

Rationale:
- The frontend is the only thing currently producing a deployable artifact.
  Deploying a JVM service requires first introducing an HTTP entry point
  (`grid/application/`, `grid/api/`, plus the framework choice deferred in
  ADR-0003 §3) — that is a separate workstream and a separate ADR.
- A static bundle deploys in seconds and exercises the full pipeline
  (CI build → artifact → platform → live URL → rollback) without dragging
  in the JVM cold-start question, the framework choice, or the database
  question.
- Per `MANIFESTO.md` ("Right-sized infra. Tear down unused environments."),
  shipping nothing JVM-side until there is a JVM endpoint to ship is the
  honest move.

A follow-up ADR will introduce the JVM hello-world deployment when the first
HTTP endpoint exists. The two deployments will then run side-by-side; the
frontend pipeline established here is the template.

### 2. Where it deploys: Cloudflare Pages

Cloudflare Pages is the v1 target. Trade-offs honestly considered:

- **Free tier covers everything.** Unlimited bandwidth, 500 builds/month,
  unlimited preview deployments. No credit card required for the project's
  foreseeable footprint.
- **French audience presence.** Cloudflare has POPs in Paris (CDG) and
  multiple secondary French cities; latency to French users is competitive
  with any provider, better than US-anchored ones.
- **PR preview deployments out of the box.** Every PR opened against `main`
  gets its own ephemeral URL. This satisfies the manifesto's "Same IaC for
  ephemeral, staging, and production — parameterized, not duplicated."
- **GitOps native.** Pages is wired to a Git provider; pushing `main`
  triggers a production build and deploy. The repo is, in fact, the source
  of truth for system state.
- **Workers integration.** When a JVM-or-edge backend is needed later,
  Cloudflare Workers (or external JVM hosting fronted by Workers) is one
  configuration step away.
- **Vendor lock-in.** Real but bounded: a static bundle is portable to any
  static host with a few lines of CI change. The IaC config (`wrangler.toml`)
  is small and replaceable. Acceptable for a sandbox.
- **Manifesto rule "Container images pinned to digest. Builds are
  deterministic."** Static bundles are not container images, so the digest-
  pinning rule does not apply directly. The deterministic-build half is
  honored via `pnpm install --frozen-lockfile` plus a pinned Node version
  in `.nvmrc` and the GitHub Actions `setup-node` step.

Rejected alternatives:

- **Vercel** — comparable feature set, but the project explicitly chose
  Vite over Next.js (ADR-0002 §1). Vercel's value-add over Cloudflare
  Pages is mostly Next.js integration, which is irrelevant here.
- **Netlify** — similar to Cloudflare Pages on features; lower free
  bandwidth ceiling, no clear edge-compute story for the JVM-eventually
  case. Slightly worse fit.
- **GitHub Pages** — no preview deployments per PR, no edge compute, no
  custom build pipeline. Too limited even for v1.
- **Fly.io / Render / Railway / Cloud Run / App Runner** — overkill for a
  static bundle; better fits for the *next* deployment ADR (the JVM
  service).
- **Self-hosted (a tiny VPS, Cloudflare Tunnel)** — adds maintenance burden
  with no offsetting gain at this stage.

### 3. How it deploys: GitHub Actions → Cloudflare Pages, schema in repo

The deployment is driven entirely by GitHub Actions. No manual platform
clicks beyond the one-time Cloudflare project creation and API-token
issuance.

Concretely (the *implementation* of which lives in a follow-up workstream;
this ADR commits to the *shape*):

- `.github/workflows/deploy-frontend.yml` runs on push to `main` and on
  `pull_request` (for previews). Steps: `actions/checkout`,
  `actions/setup-node` pinned to the version in `frontend/.nvmrc`,
  `pnpm install --frozen-lockfile`, `pnpm --filter frontend build`,
  `cloudflare/pages-action` to publish.
- Build artifacts are deterministic: lockfile committed, Node version
  pinned, no ambient state on the runner consulted.
- A `wrangler.toml` (or equivalent Pages config) lives in `frontend/` and
  is the source of truth for build settings the platform reads.
- Ephemeral environments per PR are provided by Pages automatically;
  production is the result of a push to `main` (per ADR-0001 §6,
  squash-merge only).

### 4. Promotion strategy: previews on PR, production on `main`

- **Ephemeral preview environment** — every PR against `main` gets a
  unique `pr-<n>.<project>.pages.dev` URL. Reviewers can click and play.
- **Production** — every merge to `main` triggers a production deploy.
- **Staging** — *not introduced in v1*. The manifesto's "Same IaC for
  ephemeral, staging, and production" is satisfied by ephemeral=production
  parity (same workflow, same build, same platform). When complexity
  warrants a staging tier (real users, real data), a future ADR adds it
  by parameterizing the existing workflow, not by duplicating it.

### 5. Rollback strategy: revert + redeploy, with platform fallback

- **Primary mechanism:** revert the offending commit on `main` (one PR,
  squash-merge, auto-redeploys via the workflow). Manifesto-conformant
  ("Rollback is always one click") and GitOps-pure (state is what the repo
  says).
- **Secondary mechanism:** Cloudflare Pages keeps deployment history. The
  Pages dashboard exposes a "Roll back to this deployment" button, useful
  if the offending commit is hard to revert (build broke, dependency
  poisoned). This is the escape hatch, not the default — using it
  introduces drift between repo state and live state.

### 6. Observability: minimum viable, OpenTelemetry deferred

The manifesto requires "structured logging (JSON), distributed tracing
(OpenTelemetry) from day 1, RED metrics on every service endpoint." For a
static frontend with no service endpoint, the literal reading does not
apply, but the spirit does:

- **v1 minimum:** Cloudflare Web Analytics (free, privacy-respecting,
  cookieless) enabled at the Pages project level. Captures page views,
  Core Web Vitals (LCP, CLS, INP), referrers, country buckets. No PII.
- **Browser-side error reporting** — deferred to the first interactive
  feature ADR. A static "display a title" page has no failure modes
  worth piping to Sentry / OTel.
- **OpenTelemetry browser SDK** — deferred. Cost-justified only when a
  request crosses the front/back boundary; with no API yet, there is no
  span to emit. The `frontend/src/infrastructure/telemetry/` slot is
  reserved by ADR-0002's hexagonal layout for when this lands.
- **Logs** — server-side logs do not exist (static deploy). The eventual
  JVM service ADR will reaffirm the manifesto's "structured JSON logs"
  rule.

This is a deliberate stretch of the manifesto's "OTel from day 1" wording.
Justification: OTel without a span to record is theater. The ADR-0002
slot for telemetry exists; it is wired the day there is something to
trace. The blameless-post-mortem rule kicks in if this stretch causes a
real incident.

### 7. Secret management: GitHub Actions secrets only

- The single secret needed for v1 is the Cloudflare API token (scoped to
  the Pages project, no broader Cloudflare access).
- It lives in GitHub Actions Secrets (`CLOUDFLARE_API_TOKEN`,
  `CLOUDFLARE_ACCOUNT_ID`).
- It is referenced in the deploy workflow only; never in repo code.
- Per the manifesto ("Secrets never in code. Injected at runtime. Git hooks
  prevent accidental commits"), this is sufficient until a service-side
  secret (database URL, third-party API key) appears.
- Pre-commit secret-scanning is **not** introduced in this ADR; it is added
  in the security ADR for the project, alongside the dependency-scanning
  decision.

### 8. What this ADR explicitly does not decide

- **JVM hello-world deployment** — separate ADR when the first HTTP
  endpoint exists.
- **Custom domain** — Cloudflare Pages's `*.pages.dev` subdomain is used
  in v1. Custom-domain decision (which domain, registrar, DNS strategy)
  belongs in a separate ADR alongside the first marketing/landing page.
- **Multi-region strategy** — Cloudflare Pages is multi-region by default
  via the global edge network; no per-region tuning needed at this stage.
- **Autoscaling tuning** — N/A for static; deferred to the JVM-service
  deployment ADR.
- **CDN cache headers beyond Pages defaults** — defaults are fine for a
  21 KB JS bundle; revisit when caching strategy materially affects
  perceived performance.
- **Pre-deploy security scanning** (SAST, dependency scanning) — covered
  by the project security ADR, not by deployment.
- **Disaster-recovery / backup strategy** — N/A for stateless static;
  reappears in the persistent-storage ADR.
- **Cost monitoring / budget alerts** — N/A on free tier; revisit if any
  paid feature is ever enabled.

## Consequences

### Easier

- The pipeline exists end-to-end on day one. Future workstreams ship by
  merging a PR; deploy-readiness becomes a property of the repo, not of a
  human ritual.
- Per-PR ephemeral environments make review faster and the §6a reviewer
  agent's job easier — it can quote a live URL when commenting on visual
  regressions.
- Rollback discipline is established before there is anything to roll
  back from. By the time a real incident happens, the muscle memory is
  there.
- The manifesto's GitOps rule is honored without extra tooling: pushing
  `main` is the deploy mechanism, full stop.

### Harder

- The OpenTelemetry stretch is real. The manifesto says "from day 1"; this
  ADR says "from the first request." If a future incident shows that
  observability gaps grew silently between deploys, the rule wins and an
  amendment narrows this ADR.
- Cloudflare lock-in is real. Migrating off Pages later is not free, and
  the Workers escape hatch we're banking on for the JVM-edge case may
  not match a JVM service's needs (Workers run V8, not JVM). A future ADR
  may pick a different host for the backend and front Pages with it.
- The single-host story breaks the moment we need the JVM service. The
  v2 deployment story will be two-platform unless we reconsider.

### Different

- "Production" stops being aspirational on this branch. Every PR has
  consequences for a live URL.
- The §6a reviewer's "obvious bugs" check now has a concrete artifact to
  inspect — the preview deployment — in addition to the diff. The role
  scope in ADR-0001 §6a does not change, but the data the reviewer can
  consult expands.
- Branch hygiene matters more (per ADR-0001 §2): a stale `claude/...`
  branch is now also a stale Pages preview. The workflow's cleanup step
  (Pages auto-deletes preview deploys when the PR closes) takes care of
  this without intervention.

## Notes

This ADR is revisited if any of the following occur:

- The free tier becomes insufficient (build minutes, bandwidth, or any
  Pages limit). Trigger to evaluate paid tiers or alternative hosts.
- A second deployment target is introduced (the JVM hello-world). The
  promotion / rollback / observability sections may need to harmonize
  across both.
- The OpenTelemetry deferral causes a real incident the SDK would have
  prevented or shortened. The "deferred" rule retires.
- Cloudflare materially changes the Pages product, pricing, or terms in a
  way that affects the project.

Implementation does not begin in this PR. The follow-up workstream
(`feat/deploy-frontend-cloudflare-pages` or similar) introduces:
- `.github/workflows/deploy-frontend.yml`.
- `frontend/.nvmrc`.
- `frontend/wrangler.toml` (or the equivalent Pages config).
- `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` set in repo
  Actions secrets (manual step by the maintainer).
- The Cloudflare Pages project itself, created once via the Pages
  dashboard or `wrangler pages project create` (manual step).

That follow-up is itself bounded by ADR-0001 §4 — single workstream,
single context, under 400 lines of hand-written code, conventional commits.
