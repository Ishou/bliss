# ADR-0027: Self-hosted observability backend — SigNoz

## Status

Proposed

## Context

The MANIFESTO §Observability commits the project to four practices, none of
which are wired today:

- **Structured logging** with correlation id, service name, bounded context.
  Partially shipped — JSON encoder + service/context fields exist; correlation
  IDs land in #267 (Ktor `CallId` + Logback MDC).
- **Distributed tracing** propagated across service boundaries. Nothing.
- **RED metrics** on every endpoint. Nothing.
- **Alerts on symptoms** (error rate, latency). Nothing.

In addition, two sub-rules constrain the choice of backend:

- "All instrumentation uses OpenTelemetry APIs." (`MUST`)
- "Use vendor-specific instrumentation libraries in application code." (`MUST NOT`)

Public launch (tracked in `IDEAS.md` and the launch-readiness audit) is
imminent. The first 100 users will exercise edge cases the maintainer has
never seen; without an aggregator, those errors only surface from `kubectl
logs` after-the-fact, and there is no way to alert on them. The cluster is
small Hetzner k3s (1× cx22 control plane + 1× cx32 worker after the planned
bump in PR-C of the launch-readiness rollout), so resource budget matters.

A first iteration of this plan proposed **GlitchTip + Sentry SDKs** as the
error tracker, layered on top of a separate **kube-prometheus-stack** for
metrics, **Tempo** for traces, and **Loki** for logs. That iteration was
rejected because:

- The Sentry SDK (`io.sentry:sentry-logback`, `@sentry/react`) is a
  vendor-specific instrumentation library — it sends data over Sentry's
  proprietary "envelope" wire format, not OTLP. Importing it in `api/` or in
  the frontend bundle violates the OpenTelemetry rule above.
- Stitching four separate components (Prometheus, Loki, Tempo, GlitchTip)
  also stitches four operational surfaces. For a solo maintainer, the
  cognitive load of "which tool answers which question" is itself a cost.
- ~4 GB of cluster RAM for the four-component stack is more than 50% of a
  bumped cluster's usable memory.

The decision space is therefore **a single, OTel-native, self-hostable
backend that ingests traces, metrics, logs, and exception spans over OTLP**
and presents them in one UI.

### Options considered

| Option | Wire format | Storage | Resource baseline | Error UX | Notes |
|---|---|---|---|---|---|
| **SigNoz** | OTLP | ClickHouse + ZooKeeper | ~2.0 GB RAM | Real "Errors" tab — dedupe, ownership, frequency | Single Helm chart. Active community. Used by Uber, Comcast. Built-in alerting. |
| HyperDX | OTLP | ClickHouse | ~2.0 GB RAM | Search-driven errors view, log-scoped UI | Newer, smaller community. Functionally close to SigNoz. |
| OpenObserve | OTLP | Parquet on S3-compatible object storage | ~0.5 GB RAM | Workable but less polished | Single Rust binary. Could re-use the Hetzner Object Storage already provisioned for CNPG backups. Smaller community. |
| Grafana stack (Prometheus + Loki + Tempo + Grafana) | Mixed (OTLP via collector, Prom-format scrape, Loki push) | TSDB + Loki chunks + Tempo blocks | ~2.5 GB RAM | "Filter Loki for `level=error`" — no native dedupe / ownership view | Best dashboards. Modular. Multiple components to operate. The de-facto stack for "I'll assemble it myself." |
| GlitchTip + Grafana stack | Sentry envelope + OTLP | Postgres (issues) + the above | ~2.5 GB RAM | Sentry-style errors UI | **Rejected**: GlitchTip's ingest is the Sentry protocol, which forces a Sentry SDK into application code, violating §Observability OTel rule. |
| Sentry self-hosted | Sentry envelope | Postgres + Kafka + ClickHouse + Snuba + Redis (~8 services) | ~8 GB RAM | Reference implementation | **Rejected**: same OTel-rule violation, plus 4× the resource budget. |

### Why SigNoz over the close alternatives

- **vs. Grafana stack:** SigNoz collapses four UIs into one. The Grafana
  stack's "errors as a Loki search" experience is workable but second-class
  — for a solo maintainer who will spend most of their observability time
  on errors at launch, the dedicated Errors page is the better default.
  Grafana stack stays attractive long-term once the operator wants
  best-in-class dashboards; the migration is cheap because both ingest OTLP
  identically.
- **vs. HyperDX:** SigNoz has more deployments-in-anger (Uber, Netflix, etc.)
  and a longer track record. Functionally similar otherwise.
- **vs. OpenObserve:** OpenObserve wins on resource baseline by ~1.5 GB and
  on storage architecture (parquet + object storage, no ClickHouse cluster
  to manage). Loses on error-tracking UX maturity. If the cluster budget
  proves binding post-launch, OpenObserve is the documented fallback —
  again, no application change needed.

### Why this stays manifesto-aligned

- All instrumentation enters via the **OpenTelemetry Java agent** on the
  backend (`-javaagent:/opentelemetry-javaagent.jar`) — zero SDK imports in
  `domain/`, `application/`, `infrastructure/`, or `api/`. Auto-instruments
  Ktor, JDBC, the Ktor client, and WebSockets.
- The frontend uses `@opentelemetry/sdk-trace-web` +
  `@opentelemetry/auto-instrumentations-web`, which are the OTel
  reference SDKs — not vendor-coupled.
- The exporter targets OTLP/HTTP. Switching backends later means changing
  one env var (`OTEL_EXPORTER_OTLP_ENDPOINT`); no application change.
- "Right-size infrastructure" (Ethics §Environmental Awareness): SigNoz's
  ~2 GB is near the floor for the four observability concerns combined.

## Decision

**Use SigNoz as the single self-hosted observability backend** for the
WordSparrow cluster. It ingests OTLP from both backends and the frontend,
stores in ClickHouse, and serves traces, metrics, logs, and exceptions
through one UI.

**Concrete commitments for the rollout PRs:**

1. New Helm umbrella chart at `infra/observability/` mirroring the pattern
   of `infra/matomo/` (Chart.yaml, values.yaml, values-prod.yaml,
   templates/, README.md). Pulls SigNoz's official chart as a subchart.
2. ClickHouse and ZooKeeper run **inside the SigNoz subchart**, not shared
   with anything else. ClickHouse is a different OLAP engine than the CNPG
   Postgres used by `grid/api`; sharing makes no operational sense.
3. The OpenTelemetry **Collector** is deployed as part of the same chart
   (it ships with SigNoz). Apps export to the collector's OTLP endpoint
   inside the cluster; the collector batches, scrubs PII, and forwards to
   SigNoz's ingestion service.
4. Backend instrumentation: OTel Java agent JAR copied into the runtime
   image at build time and added to `JAVA_OPTS` in the Helm chart.
   `OTEL_SERVICE_NAME` set per service; `OTEL_RESOURCE_ATTRIBUTES` includes
   `bounded_context` (matches the existing Logstash field).
5. Frontend instrumentation: `@opentelemetry/sdk-trace-web` plus the
   auto-instrumentation packages for `fetch`, `xml-http-request`,
   `document-load`, and `user-interaction`. Initialised in
   `infrastructure/observability/otel.ts` from the composition root, gated
   on `import.meta.env.PROD` so dev runs noop unless explicitly opted in.
6. Browser→cluster OTLP traffic uses a public ingest subdomain
   (`otlp.wordsparrow.io`) terminating at the OTel collector. CORS allowlist
   on the collector restricts to `wordsparrow.io` apex + `www`.
7. Alerts: SigNoz's built-in alert rules cover the launch requirement (5xx
   rate symptom alert). Notification channel: SMTP via Gmail with an app
   password (separate Secret, ADR-0028 covers admin-credential handling).
8. PII scrubbing happens at the **collector** layer, not in application
   code: drop `?email=`, `?token=`, `?session=` query parameters; redact
   `Authorization` and `Cookie` request headers; never capture request
   bodies. This keeps app code OTel-vanilla and centralises the privacy
   policy in one config file.
9. Existing structured-log fields (`service_name`, `bounded_context`,
   `correlation_id`) are emitted as OTel log attributes by the agent's
   Logback bridge so the SigNoz UI shows them as filters.

**The launch-blocker test:** trigger an unhandled exception in `grid/api`,
expect a deduplicated row in SigNoz Errors with the same `correlation_id`
that appears in `kubectl logs` and in the response header from PR #267.

## Consequences

**Positive:**

- One UI for traces, metrics, logs, errors. One product to learn.
- Single OTLP wire format; backend swap is a one-env-var change.
- All instrumentation is auto, no SDK imports — domain and application
  layers stay manifesto-clean.
- ~2 GB RAM total, fits in the bumped cluster (PR-C).
- Built-in alerting removes one component (no separate AlertManager).

**Negative / trade-offs:**

- ClickHouse is a new operational surface. Mitigated by: SigNoz's chart
  configures it for us; backups are not critical (observability data is
  short-retention by design — 7d traces, 30d metrics — and is rebuilt by
  apps on the next deploy).
- SigNoz's error-tracking UI is less polished than Sentry's. Acceptable
  given the OTel constraint; if it ever becomes the bottleneck, an
  OTel-aware alternative (e.g. HyperDX or — harder — Sentry's open-source
  fork once it ships OTLP ingest natively) is a swap, not a rewrite.
- ZooKeeper dependency in the SigNoz chart adds one more pod. ~150 MB.
- Browser SDK adds bundle weight. Auto-instrumentation packages are
  ~25 KB gzipped — acceptable; tracked as a chunk-budget item in the
  follow-up performance work.

**Future ADRs unblocked:**

- A future ADR on PII redaction policy (specific patterns, retention) once
  enough traffic has shaped the redaction list.
- A future ADR if/when we move to OpenObserve or Grafana stack (the
  motivation would have to be measurable: cluster pressure or a missing
  feature). Reference this ADR as superseded.
- Sealed-secrets or SOPS for IaC-pure secret management (currently
  out-of-scope, see ADR-0028).
