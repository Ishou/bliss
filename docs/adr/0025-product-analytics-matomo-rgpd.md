# ADR-0025: Product Analytics with Self-Hosted Matomo and RGPD Posture

## Status

Accepted

## Context

Bliss collects almost no behavioural data today. The audit done while
brainstorming this ADR found:

- Anonymous `sessionId` (UUID v7, ADR-0021) and editable pseudonym in browser
  `localStorage`.
- `puzzle_hint_usage` rows in Postgres keyed by `sessionId`, no expiry.
- In-memory multiplayer lobby state (ADR-0018), evicted after 30 min idle.
- No third-party tracking SDKs, no cookies, no IP logging in application code.

Decisions about generation quality, hint balance, completion rate by grid
size, and multiplayer engagement are currently made without numbers. Adding
analytics is the right answer, but the moment Bliss starts processing user
behaviour at scale it must demonstrably comply with RGPD — not rely on the
manifesto's stated intent (MANIFESTO.md §"Privacy by Design").

The audit also surfaced compliance gaps that exist even without analytics:
no privacy notice, no retention schedule, no erasure path, no
sub-processor register. Adding a privacy-conscious analytics tool is a
forcing function for closing all four gaps. This ADR scopes "analytics +
the RGPD pieces analytics inherently triggers" as one decision; broader
compliance work (DSAR export, breach-notification runbook, formal DPA
register UI) is intentionally deferred.

### Alternatives considered

**PostHog Cloud EU.** Polished product, free tier covers projected scale
(1M events/month), EU data residency, signed DPA. Rejected as primary
choice for two reasons: (1) PostHog is not on the CNIL audience-measurement
exemption list, so the default configuration requires a CNIL-compliant
consent banner with refuse-as-easy-as-accept, adding UX friction and a
maintenance surface that this project does not need; (2) it adds an EU
sub-processor to the data path, which conflicts with the manifesto's
preference to "collect the minimum [and] not share without consent."
Defensible alternative if Bliss ever needs session replay or feature
flags at scale.

**PostHog self-hosted.** Removes the sub-processor concern but introduces
ClickHouse + Kafka + Redis + MinIO as new operational components for a
solo-dev project. PostHog's own documentation actively discourages
self-hosting for new deployments. Ceiling far above what Bliss can
realistically use in year one.

**DIY (Postgres `analytics_events` table + Metabase or Grafana on CNPG).**
Most architecturally elegant: zero new vendors, reuses existing CNPG
Postgres, schema-first culture (ADR-0003) extends naturally to versioned
event names. Rejected because the RGPD-machinery work (privacy notice
opt-out wiring, DSAR-style export, anonymization helpers, retention
enforcement) would be hand-built; Matomo ships all of that as part of its
"GDPR Manager" feature. The DIY ceiling is also limited for ad-hoc product
questions without significant SQL effort.

**Umami self-hosted.** MIT-licensed and tiny, but its event/funnel model is
too thin for the product-behaviour questions this work needs to answer
(completion rate by grid size, hint correlation with completion, multiplayer
return-rate). Better suited as a pageview sidekick than a primary tool.

**Matomo Cloud (Matomo's own EU SaaS).** Same product, takes one
sub-processor on. Rejected on the same self-hosting principle as PostHog
Cloud, but the trade is genuinely close; revisit if cluster ops capacity
ever becomes the constraint.

## Decision

### 1. Tool

Self-hosted [Matomo](https://matomo.org/) on the existing k3s cluster
(ADR-0009), backed by a dedicated MariaDB instance also on the cluster.
MariaDB is required by Matomo (Postgres unsupported) and runs separately
from CNPG.

### 2. CNIL consent-exempt configuration

The deployment is configured so it qualifies for the CNIL audience-
measurement consent exemption (CNIL deliberation
[2020-091](https://www.cnil.fr/fr/cookies-solutions-pour-les-outils-de-mesure-daudience)).
Concretely, the Matomo `config.ini.php` mounted at deploy time must enforce:

- IP anonymization at 2 bytes (the CNIL recommended floor).
- DoNotTrack honoured.
- No browser fingerprinting plugins (`Browser plugin support` off).
- No cross-site or cross-domain visitor identification.
- Tracker cookies disabled by default (`disableCookies()` on the JS side).
- Geolocation reduced to country granularity; no city-level GeoIP.

If any of these knobs is loosened, the exemption is lost and a CNIL-
compliant consent banner becomes required before the change ships. Such a
change requires a follow-up ADR.

### 3. Identifier strategy

Two ingestion paths, both designed to avoid persistent cross-session
identifiers:

- **Frontend** uses Matomo's cookieless mode. Visitor identity is derived
  by Matomo from a daily-rotated salted hash of IP+UA, which is the
  standard CNIL-aligned pattern.
- **Backend** (server-side events from `LobbyEvent` and grid use cases)
  computes a daily-rotated salted hash of `sessionId` and passes it as the
  Matomo `_id` field. Same browser is consistent within a day; not linkable
  across days. The `sessionId` itself is never sent to Matomo.

### 4. Architecture

- A new `AnalyticsEventSink` port in `game/application/ports` and
  `grid/application/ports` (consistent with all existing output ports:
  `LobbyRepository`, `PuzzleProvider`, `Clock`, `PresenceBroadcaster`).
- Sealed `AnalyticsEvent` hierarchy in domain, version-suffixed names.
- A `MatomoAnalyticsAdapter` in each context's `infrastructure` layer
  forwards events to Matomo's HTTP Tracking API. Fire-and-forget on a
  supervised `CoroutineScope`; analytics never blocks the domain or
  fails a request.
- A `NoopAnalyticsAdapter` for tests and dev environments without Matomo.
- Konsist arch test enforces no Matomo imports in the domain layer.

### 5. RGPD remediation bundled with this work

The following pieces ship alongside the analytics rollout because adding a
processing activity makes them blockers:

- **Privacy notice** (`docs/privacy/privacy-notice.md`, rendered to
  `/confidentialite` and `/privacy` routes). French primary.
- **Retention schedule** (`docs/privacy/retention-schedule.md`):
  - `puzzle_hint_usage` rows: 90 days from `updated_at`, enforced by
    nightly Kubernetes CronJob.
  - Matomo visit-level data: 13 months (CNIL audience-measurement
    guidance), configured in Matomo admin.
  - Lobby state: unchanged (in-memory, evicted after 30 min idle per
    ADR-0018).
- **Sub-processors register** (`docs/privacy/sub-processors.md`): Hetzner
  Online GmbH (hosting), Cloudflare Inc. (DNS, CDN). No Matomo Cloud.
- **Erasure endpoint**: `DELETE /v1/sessions/{sessionId}` on `grid/api`
  removes hint-usage rows. Active `Live.deleteVisits` is deliberately
  omitted: the daily-rotated salted hash already makes prior-day visits
  non-attributable to any individual, and generating a fresh `sessionId`
  client-side after erasure breaks same-day linkage without an active
  Matomo API call. The implementation cost of calling `Live.deleteVisits`
  (expanded adapter, API dependency) outweighs the marginal compliance
  gain. Only the hint-usage rows — which are the only data keyed to a
  user-controlled `sessionId` — are deleted. This design is disclosed
  to users in the privacy notice. Frontend exposes "Effacer mes données"
  in settings.

### 6. Out of scope

These are deliberate deferrals to avoid scope creep:

- Full DSAR export (Articles 15 and 20 portability). Not needed pre-accounts.
- Formal DPA register UI; the markdown register is enough at this stage.
- Breach-notification runbook (separate incident-response spec).
- OpenTelemetry / RED metrics rollout (ADR-0007 scope, distinct from
  product analytics).
- Cookie consent banner. Not required under the chosen Matomo
  configuration; only revisit if the configuration is loosened.

## Consequences

### Easier

- **Numbers without a banner.** Product decisions can be informed by data
  without a consent UX in front of every visitor.
- **Compliance machinery is product, not code.** Matomo's GDPR Manager
  ships erasure, anonymization, opt-out, and DSAR-lite features as a
  packaged UI, removing several engineering items.
- **Zero new sub-processors.** The audit register stays at two entries
  (Hetzner, Cloudflare), both of which Bliss already depends on.
- **Schema-first culture extends naturally.** Versioned event names
  (`game:lobby_created:v1`) live in `docs/analytics/event-taxonomy.md`
  alongside OpenAPI/AsyncAPI specs, fitting ADR-0003.

### Harder

- **MariaDB joins the cluster.** A second relational engine alongside
  CNPG Postgres. New backup/restore drill, new failure mode, new minor
  upgrades to track. Mitigation: dedicated to Matomo only; do not extend
  it to product workloads.
- **CNIL configuration is load-bearing.** The exemption depends on the
  `config.ini.php` settings. A casual admin-UI change (enable a
  fingerprinting plugin, expand GeoIP granularity) silently invalidates
  the consent posture. Mitigation: configuration shipped via ConfigMap
  in Helm so changes go through Git and PR review.
- **Two ingestion paths to maintain.** The frontend tracker and the
  backend adapter both forward events; they need to not diverge in event
  names. Mitigation: single source-of-truth in
  `docs/analytics/event-taxonomy.md`.
- **Operational footprint grows.** ~1 GB RAM Matomo + ~1 GB MariaDB,
  ~10 GB PVC each. Affordable on Hetzner but real.

### Different

- **The `puzzle_hint_usage` table now has a published lifecycle.** From
  "lives forever" to "90 days from last update." Long-tail analyses that
  assumed unlimited history must adapt or move to Matomo aggregates.
- **The privacy notice becomes the canonical source of truth** for what
  Bliss collects. Future features that introduce new processing must
  update it as a precondition to merge, the same way OpenAPI changes do.
