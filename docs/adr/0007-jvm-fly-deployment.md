# ADR-0007: JVM API Deployment on Fly.io

## Status

Accepted

## Context

ADR-0004 deployed the frontend static bundle to Cloudflare Pages and
explicitly deferred the JVM hello-world deployment: *"separate ADR when
the first HTTP endpoint exists."* That endpoint is now imminent — ADR-0006
(parallel workstream) picks Ktor as the JVM HTTP framework, the OpenAPI
contract from PR #18 declares `GET /v1/puzzles/{puzzleId}`, and the user
flow needs front↔back HTTP traffic.

Cloudflare Pages cannot run JVM bytecode (Workers runtime is V8-only), so
the API must live on a different platform. This ADR commits to that
platform plus the architectural shape (front + back split, persistence,
realtime posture) so subsequent implementation workstreams have a single
spec to follow.

The selection space considered:

- **Where the JVM API runs**: Fly.io, Cloud Run, Render, Railway,
  AWS App Runner, self-hosted VPS (Hetzner / OVH).
- **Single platform vs. split**: serve both the static bundle and the API
  from one place, or keep Cloudflare Pages for static and the new
  platform for the API only.
- **Realtime transport for v1**: SSE only, WebSocket, or socket.io —
  affects platform requirements (sticky sessions, connection lifetime
  caps).
- **Persistence**: bundled with the platform (Fly Postgres,
  Cloud SQL, Render Postgres), self-hosted, or external managed
  (Neon / Supabase).

## Decision

### 1. What is deployed: the Kotlin/Ktor API plus Fly Postgres

The unit being deployed is the JVM service in `grid/api/` (introduced by
the implementation workstream after this ADR and ADR-0006 land). State
lives in a **Fly Postgres** cluster co-located in the same Fly app and
attached over Fly's internal `.flycast` private network — the database
has no public IP.

The frontend continues to deploy via the existing
`.github/workflows/deploy-frontend.yml` to Cloudflare Pages
(ADR-0004); this ADR introduces a sibling pipeline for the API and
**does not change the frontend deploy in any way**.

### 2. Where: Fly.io, Paris (CDG)

Fly.io in the **CDG region** is the v1 target. Trade-offs honestly
considered:

- **CDG region** for the French audience. Cloudflare Pages already
  serves the bundle from Paris POPs; this keeps the round-trip short
  for the API call too.
- **WebSocket / SSE first-class.** Fly does not cap connection
  lifetimes (Cloud Run cuts at 60 minutes — broken for live-scoreboard
  pushes) and does not auto-sleep idle services (Render's free tier
  does — broken for SSE).
- **Always-on machine model** fits "users + scoreboard + monetization"
  better than scale-to-zero. JVM cold start (~1–3 s plain) breaks
  live HTTP UX every time the machine wakes.
- **Pricing reality.** Fly removed its free allowance in late 2024;
  pay-per-use with a $5/month minimum credit. For the v1 footprint
  (1× shared-CPU 256–512 MB machine + smallest-tier Fly Postgres),
  expect $5–15/month all-in. Acceptable cost for a real product
  beyond hello-world.
- **IaC native.** `fly-apps/fly` Terraform provider for app, machine,
  Postgres, IP allocation, certs. `fly.toml` checked into the repo
  for build / service / healthcheck config. `flyctl deploy` from
  GitHub Actions, parallel to the existing Cloudflare deploy workflow.
  Same operational model as the frontend; nothing new to learn.
- **Fly Postgres** is bundled — no separate DB platform decision in
  v1. Single tenant, single region; replication and DR are deferred
  to a future ADR if the project scales beyond a single-machine
  footprint.

Rejected alternatives:

- **Cloud Run + Cloud SQL.** "Cheaper at zero traffic" but JVM cold
  starts on every wake hurt UX, the 60-minute connection cap breaks
  live-update transports, and Cloud SQL pricing makes the DB the
  bigger cost anyway (~$10–30/month minimum).
- **Render web + Render Postgres.** Simpler UX, but no Paris region
  (Frankfurt only), ~2× the price ($14/month minimum for an
  always-on web service + DB), and the free tier sleeps services
  after 15 minutes — incompatible with SSE.
- **Railway.** Solid DX, but slim EU presence and no Paris region.
- **Self-hosted VPS** (Hetzner, OVH, etc.). Cheapest (~€4/month) but
  the manifesto's "immutable deployments / no SSH-to-prod / GitOps"
  rules become real friction without bringing NixOS or full Docker
  Compose discipline. Trades €5/month for ops time the maintainer
  doesn't have at this stage.
- **AWS App Runner.** Comparable to Cloud Run; same cold-start +
  pricing trade-offs, weaker EU presence.

### 3. How: GitHub Actions → flyctl deploy

The deployment is driven entirely by GitHub Actions, mirroring the
frontend pattern:

- **`.github/workflows/deploy-api.yml`** (introduced by the implementation
  workstream — not this ADR) runs on push to `main` (production) and
  on `pull_request` (preview).
- Steps: `actions/checkout`, `actions/setup-java` for JDK 21,
  `gradle/actions/setup-gradle`, `./gradlew :grid:api:shadowJar`,
  `superfly/flyctl-actions/setup-flyctl`, `flyctl deploy --remote-only`.
- **Build artifacts deterministic**: `gradle.lockfile` committed,
  JDK pinned in `actions/setup-java`, no ambient state.
- **`fly.toml`** at the JVM context root (or repo root) declares build
  source (Dockerfile path), services (HTTP on 8080), healthcheck
  (`/v1/health` once that endpoint exists), `auto_stop_machines = false`
  (always-on for v1).
- **Container images pinned to digest** per the manifesto. The build
  produces a Docker image referencing a SHA-pinned JDK base
  (Eclipse Temurin); `fly deploy` uploads this image to Fly's registry.

### 4. Architecture: Cloudflare-Pages front + Fly-back split

```
                              ┌────────────────────────────────────┐
   wordsparrow.io  ───────────│  Cloudflare Pages (static bundle)│
                              └────────────────────────────────────┘
                                          │ HTTPS (XHR / SSE)
                                          ▼
                              ┌────────────────────────────────────┐
   api.wordsparrow.io  ───────│  Fly.io (Kotlin/Ktor JVM)        │
                              │  + Fly Postgres (.flycast private) │
                              └────────────────────────────────────┘
```

- Frontend stays at `wordsparrow.io` on Cloudflare Pages (ADR-0004
  unchanged).
- API at `api.wordsparrow.io` on Fly. DNS: a `cloudflare_dns_record`
  Terraform resource adds `CNAME api.wordsparrow.io →
  wordsparrow-api.fly.dev` to the existing Cloudflare-managed zone.
  No proxy / orange-cloud — DNS-only mode keeps the API path clean
  for SSE and any future WebSocket without a Cloudflare middleman.
- TLS at Fly: Let's Encrypt cert auto-issued and rotated for
  `api.wordsparrow.io`. No certbot, no renewal cron.
- **CORS for v1**: API responds with
  `Access-Control-Allow-Origin: https://wordsparrow.io` and the standard
  preflight allow headers / methods for the endpoints declared in the
  OpenAPI spec. Bearer-token auth (when the `accounts/` context lands)
  avoids cookie cross-origin pain for monetization flows.
- **Same-origin proxy via a Cloudflare Worker** is documented as a
  future option (cookie-session monetization) but **deferred** —
  introducing a Worker just to remove CORS headers is overhead the v1
  doesn't need.

### 5. Promotion strategy: previews on PR, production on `main`

- **Preview**: each PR runs `flyctl deploy` against a per-PR app
  (`wordsparrow-api-pr-${PR_NUMBER}`); a workflow step on
  `pull_request: closed` tears it down. Reviewers get a
  `https://wordsparrow-api-pr-N.fly.dev` URL paired with the
  Cloudflare Pages preview URL on the same PR.
- **Production**: push to `main` deploys to the `wordsparrow-api` app
  at `api.wordsparrow.io`.
- Same workflow, different `--app` flag — manifesto-conformant
  ("Same IaC for ephemeral, staging, and production").
- **Staging tier**: not introduced in v1, same posture as ADR-0004 §4.

### 6. Rollback strategy

Per the manifesto's "Rollback is always one click" rule:

- **Primary mechanism**: revert the offending commit on `main` via PR.
  The deploy workflow re-runs and pushes the prior JAR. GitOps-pure;
  repo state matches live state.
- **Escape hatch**: `flyctl releases list && flyctl releases rollback
  <id>` from the maintainer's machine. Introduces drift between repo
  state and live state; a follow-up PR is required to reconcile.
- **Database migrations** are **backward-compatible per the manifesto**
  (expand-and-contract). A failed deploy can roll back the JAR
  without rolling back the schema; the prior JAR must still understand
  the newer schema. This rule is binding from the first migration; the
  migration tooling choice (Flyway / Liquibase / kotlinx-Exposed
  migrations) is its own ADR with the persistence workstream.

### 7. Observability

- **Structured JSON logs** per the manifesto. Ktor's `CallLogging`
  plugin + a JSON encoder (`logstash-logback-encoder` or equivalent).
  Fly captures stdout and routes to its log aggregator
  (`flyctl logs` for ad-hoc tail). A long-term log sink ADR comes
  later if log volume justifies one.
- **Distributed tracing (OpenTelemetry)**. ADR-0004 §6 stretched
  the manifesto's "OTel from day 1" rule for a static frontend
  with no spans to record. Once the API is live, the front↔back
  call **is** the first real span. The implementation workstream
  introduces:
  - Server side: Ktor `OpenTelemetry` plugin emitting spans per
    handled request.
  - Client side: a browser OTel SDK in the frontend's
    `infrastructure/telemetry/` slot reserved by ADR-0002 §7.
  - Collector: a free-tier hosted backend (Honeycomb, Grafana
    Cloud, or self-hosted Tempo/Jaeger on the same Fly app).
    Picked in the implementation workstream.
  This ADR commits to "OTel ships with the API implementation
  workstream", not before — the deferral retires here.
- **RED metrics on every endpoint** per the manifesto.
  Ktor `MicrometerMetrics` plugin emitting Prometheus-format metrics
  on a separate internal port. Scrape backend chosen in the
  implementation workstream.

### 8. Secret management

- **`FLY_API_TOKEN`** in GitHub Actions Secrets — the deploy token,
  scoped to the Fly app (not org-wide). Created once via `flyctl
  tokens create deploy --app wordsparrow-api`.
- **App-level secrets** (Postgres password, future Anthropic API key,
  Stripe key, OAuth client secret) injected via `flyctl secrets set`
  and consumed in Ktor as environment variables. Their *names and
  bindings* live in repo code (`fly.toml`'s expected env table plus
  a section in `docs/deploy.md`); their *values* never leave Fly's
  encrypted secret store.
- **Gitleaks pre-commit hook** (already shipping) catches accidental
  commits of any of these.
- Same model as ADR-0004 §7. No new pattern here; consistency with
  the frontend's secret story.

### 9. Realtime posture: SSE only for v1

Per ADR-0006 (parallel workstream), the v1 realtime transport is
**Server-Sent Events** — one-way push from server to client over a
long-lived HTTP connection. WebSocket and `socket.io` are deferred to
a future ADR with the multiplayer feature.

Deployment-side consequences:
- **Single Fly machine** is sufficient. SSE doesn't need sticky
  sessions; each connection is independent.
- **No Redis** for pub-sub.
- **No socket.io adapter** dependency in the JVM dep set.
- **Autoscale** stays simple: when a second machine is needed for
  load, SSE clients reconnect to whichever; no sticky-routing needed.

If multiplayer ships and demands true bidirectional WebSocket /
socket.io, the deployment story expands then — sticky sessions,
possibly Redis, and an ADR amendment.

### 10. What this ADR does not decide

- The Kotlin HTTP framework (ADR-0006, parallel workstream).
- Schema design for new endpoints (separate schema PRs after both
  ADRs land, per ADR-0001 §3 schema-first).
- DB migration tooling (Flyway / Liquibase / equivalent) — chosen
  with the persistence workstream.
- Authentication / authorization (`accounts/` context, separate ADR).
- The Anthropic SDK / puzzle-generation pipeline.
- Disaster recovery / multi-region replication.
- Cost monitoring / budget alerts.
- Custom-domain decisions beyond the apex `wordsparrow.io` already
  attached and the new `api.` subdomain introduced here.

## Consequences

### Easier

- The JVM API has a defined home. Implementation workstreams ship
  by appending Terraform resources and a workflow file; no per-PR
  deployment-target relitigation.
- Each platform plays to its strengths: Cloudflare's CDN edge for
  static, Fly's always-on machine for stateful API. No square peg
  into round hole.
- WebSocket / SSE work natively from day one. No connection-cap
  surprises, no service-sleep surprises.
- Postgres is bundled — no separate DB-platform ADR for v1, no
  dual-vendor key management.
- The maintainer's mental model stays simple: two platforms, both
  Terraform-managed, both deployed via GitHub Actions, both with
  one-click rollback.

### Harder

- **Two-platform deploy.** Two sets of secrets to rotate
  (`CLOUDFLARE_API_TOKEN` + `FLY_API_TOKEN`), two dashboards to check
  during incidents, two Terraform providers to keep current.
- **Cost stops being free.** ~$5–15/month from day one; budget
  monitoring becomes relevant at v1 + 1.
- **DNS becomes load-bearing in a new way.** The `api.wordsparrow.io`
  CNAME is the joining point of the two-platform architecture; if
  that record disappears, the frontend's API calls fail even though
  Cloudflare Pages and Fly are both healthy.
- **PR previews must spin up both sides** to be useful — frontend
  preview pointing at production API is not a meaningful preview for
  an API-touching PR.

### Different

- **The frontend stops being independently deployable** in any
  meaningful product sense once it consumes the API. A frontend
  PR's preview without a matching API preview is a half-test.
- **Observability becomes a first-class concern** — once the API
  exists, the OTel deferral from ADR-0004 §6 retires. Spans, metrics,
  logs all need a place to go before real users land.
- **Cost is no longer "$0 always"** — the project crosses the
  free-tier line. Worth flagging to anyone forking the repo: a
  fresh fork's `tofu apply` will start metering Fly billing.

## Notes

This ADR is revisited if any of the following occur:

- Fly cost grows past ~$50/month while still on v1 footprint —
  revisit machine sizing or alternate host.
- WebSocket / socket.io becomes a feature requirement — ADR
  amendment for sticky sessions, possibly Redis, possibly
  multi-machine routing.
- A real outage shows the single-machine + single-region
  Postgres setup is insufficient — DR / replication ADR.
- Cloudflare or Fly materially change their pricing or product —
  re-evaluation.
- The OTel "free-tier hosted backend" bet doesn't pan out at
  reasonable cardinality — pick a different collector.

Implementation workstreams that follow this ADR (each its own PR,
each under the 400-line cap):

1. **`feat/grid-api-ktor-skeleton`** — bring up the Ktor app with one
   `GET /v1/health` endpoint, build via Gradle as a `shadowJar`,
   package as a Docker image. Stays inside `grid/api/`.
2. **`feat/infra-fly-app`** — Terraform for the Fly app, the Fly
   Postgres cluster, the `cloudflare_dns_record` for
   `api.wordsparrow.io`, plus the deploy workflow
   `.github/workflows/deploy-api.yml`.
3. **`feat/grid-api-random-puzzle`** — schema PR amending OpenAPI for
   the random-puzzle endpoint (per ADR-0001 §3 schema-first), then
   the Ktor handler that wires the existing `grid/domain/` generator
   to HTTP.
4. **`feat/frontend-grid-fetch-from-api`** — frontend wires to the
   new endpoint via the `openapi-fetch` client already scaffolded in
   PR #18, replacing `SAMPLE_PUZZLE`.

The order matters: 1 → 2 → 3 → 4. Workstreams 1 and 2 can interleave
but the deploy fails until both are in place; mark workstream 1 as
"deploy-ready but not yet deployed" in its PR body.
