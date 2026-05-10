# ADR-0033: Frontend OTel browser SDK + public OTLP ingest

## Status

Accepted

## Context

PR-E auto-instrumented `grid-api` and `game-api` via the OTel Java
agent — backend traces flow into SigNoz. Frontend errors and slow
interactions are still invisible: when a user reports "the grid
froze", we have no traces from their session, no joined view of the
client-side fetch + the server span, and no way to spot regressions
in browser performance after a deploy.

The plan in `docs/adr/0027-observability-backend-signoz.md` (PR-F)
is to ship the OTel browser SDK on `wordsparrow.io` and forward
spans to the SigNoz collector.

This requires somewhere for browser POSTs to land. Browsers run on
the public internet; the SigNoz OTel collector runs in-cluster and
isn't reachable from outside. We need a public ingress for OTLP
ingest, which is not a thing the manifesto's "alert on symptoms"
posture had pre-validated.

## Decision

### 1. Endpoint shape

A new ingress at **`https://otlp.wordsparrow.io`** forwards `POST
/v1/traces` to the SigNoz-bundled `otel-collector` Service on port
4318 (the standard OTLP/HTTP receiver port; default config of the
SigNoz subchart).

Only `/v1/traces` is exposed. `/v1/metrics` and `/v1/logs` (the
other OTLP/HTTP receivers) stay private. Browser metric and log
emission has shaky maturity; spans are the high-leverage signal. We
turn the others on later by extending the Ingress's `paths` if and
when we want browser-emitted metrics/logs.

The ingress is rolled out as part of the existing `infra/observability/`
umbrella chart so its lifecycle stays attached to SigNoz proper. A
dedicated chart would be over-engineering for one Ingress.

### 2. Threat model and mitigations

A public, unauthenticated OTLP endpoint is a free billable ingestion
target. Anyone on the internet can POST spans to fill ClickHouse and
cost us money or denial-of-service the backend.

The layered controls (defense in depth, none individually sufficient):

| # | Control | Where | Stops |
|---|---------|-------|-------|
| 1 | CORS preflight rejection | ingress (nginx-ingress annotations) | Browser-issued cross-origin POSTs from non-`wordsparrow.io` pages |
| 2 | Server-side Origin allow-list | ingress (nginx server-snippet) | Lazy abusers who don't bother spoofing `Origin` |
| 3 | Per-IP rate limit (5 rps, burst 50) | ingress (`limit-rps`) | Single-IP floods |
| 4 | 1 MB request body cap | ingress (`proxy-body-size`) | Oversized-payload CPU amplification |
| 5 | Browser sampler at 10% | frontend SDK (PR-F.2) | Cuts legitimate traffic by 10× — fewer in-band requests + smaller backend bill |
| 6 | Collector resource limits + ClickHouse storage limits | chart defaults | Hard ceiling on blast radius if 1–4 fail |

Curl/HTTP scripts can spoof `Origin` and bypass 1+2. They cannot
trivially bypass 3 (would need many IPs). They cannot bypass 4 or 6.

This is not security; it's friction. A determined attacker with a
botnet can still abuse this endpoint. The escape hatch is documented
in §5 ("if abuse appears").

### 3. Alternatives considered

**A. Reuse `api.wordsparrow.io` with a `/otlp/*` path.** Saves a
subdomain + a TLS cert. Downside: `api.wordsparrow.io` is grid-api's
public REST surface. Mixing OTel ingest into it conflates two concerns
in nginx config, in DNS records, and in operational runbooks ("is
api.wordsparrow.io down?" → which thing). Vetoed for separation of
concerns.

**B. Same-origin proxy** — frontend POSTs to `/otlp/v1/traces` on
`wordsparrow.io`'s own origin, a proxy at the SPA-hosting layer
forwards to the cluster's collector. Cleanest from a CORS standpoint
(no preflight at all). Downside: the SPA today is hosted via the same
cluster ingress; this would mean a proxy on the `wordsparrow.io`
Ingress that talks back into the cluster's collector. Doable but adds
a second forwarding rule in the SPA host's config and an extra hop
in the path. Not worth the savings; revisit if the SPA hosting
architecture changes.

**C. Auth proxy issuing short-lived ingest tokens.** A small service
that mints per-session tokens (signed with a private key the cluster
holds) on the SPA's first load; the SDK includes the token in
`Authorization`. The auth proxy at the OTLP ingress validates +
forwards. Right answer for high-abuse-risk scenarios. Over-engineered
for our launch volume; ~1–2 weeks of work for an attack we haven't
seen. Revisit if mitigations 1–6 prove insufficient.

**D. Dedicated SaaS error tracker (Sentry, Honeycomb, Datadog).**
Vetoed by ADR-0025's zero-new-sub-processors stance and ADR-0027's
"OTel-only, self-hosted" framing.

### 4. Frontend SDK shape (PR-F.2 plan)

The browser SDK piece is split into a follow-up PR (PR-F.2) so this
PR-F.1 ships the plumbing alone — a clean review surface, plus a
working endpoint for early manual testing via curl before frontend
code lands.

Planned shape (will be re-validated when PR-F.2 lands):

- `@opentelemetry/sdk-trace-web` configured with a
  `BatchSpanProcessor`, `OTLPTraceExporter` pointing at
  `VITE_OTEL_OTLP_ENDPOINT`, sampler
  `TraceIdRatioBasedSampler(0.1)`.
- Auto-instrumentations: `@opentelemetry/instrumentation-fetch`,
  `@opentelemetry/instrumentation-document-load`,
  `@opentelemetry/instrumentation-user-interaction`.
- `W3CTraceContextPropagator` so the `traceparent` header propagates
  to grid/game APIs and joins with backend spans.
- Service name: `frontend`. `bounded_context: ui` resource attribute
  to mirror the backend's MDC tagging.
- No-op when `VITE_OTEL_OTLP_ENDPOINT` is empty (dev / preview /
  pre-PR-F.2 prod) — same opt-in idiom as `VITE_MATOMO_URL` in
  `matomoTracker.ts`.

### 5. Operational plan

1. Bootstrap: PR-F.1 lands → `helm upgrade observability` → a new
   Ingress materializes, cert-manager issues the TLS cert, external-dns
   publishes the A record. ~5 minutes.
2. Manual smoke test (no frontend code yet): `curl -i -X POST
   https://otlp.wordsparrow.io/v1/traces -H "Origin:
   https://wordsparrow.io" -H "Content-Type: application/x-protobuf"
   --data-binary @empty.bin` → expect HTTP 200 with `Access-Control-
   Allow-Origin` header. Same curl with a different Origin → 403.
3. PR-F.2 lands → frontend ships the SDK → real browser spans appear
   in SigNoz under `service.name=frontend` joined to the backend
   `traceparent`.
4. **If abuse appears**: the cheapest mitigation is to disable the
   Ingress (`ingress.otlpPublic.enabled: false` in
   `values-prod.yaml`), which immediately stops accepting traffic.
   Then ship one of:
   - Increase the per-IP rate limit cap downward (`limitRps: 1`).
   - Move to alternative B (same-origin proxy) — frontend keeps
     working, attacker loses the public endpoint.
   - Move to alternative C (auth proxy) — proper fix, weeks of work.

## Consequences

**Easier:**
- Browser-side spans flow into the same SigNoz UI that already shows
  backend spans. End-to-end traces (browser fetch → grid-api span →
  ClickHouse span) become a single click.
- The endpoint is decoupled from any specific frontend implementation
  — replacing the SDK with a different OTel-compatible client doesn't
  touch the ingress.

**Harder:**
- We've added one more public ingress to the surface area we need to
  monitor for abuse. Mitigation: the SigNoz dashboard itself can show
  if `frontend` span volume spikes anomalously.
- TLS cert renewal failures on `otlp.wordsparrow.io` would silently
  break browser-side tracing (TLS error → SDK retry → blocked CORS
  preflight → drops). Add a follow-up alert on cert-manager renewal
  failures; tracked in the launch-readiness backlog.
- The `server-snippet` annotation requires ingress-nginx's
  `--enable-snippets` flag (default true on our cluster, but
  ingress-nginx upstream is moving away from snippet annotations).
  When ingress-nginx eventually disables snippets by default, the
  Origin allow-list moves to either ConfigMap-based config or
  alternative B/C.

**Different:**
- We're now operating an "open by policy, closed by friction" public
  endpoint, which is a different posture from every other endpoint we
  run (all of which are either authenticated or owned by a public
  product surface). The friction-not-security framing is documented
  in §2 so future-us doesn't mistake the controls for actual auth.
