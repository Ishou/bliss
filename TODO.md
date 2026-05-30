# TODO

Long-horizon items the team has identified but isn't ready to pull into
a spec yet. Each entry is a one-line statement of the gap, a short
"why later" rationale, and the contexts/paths it will touch when its
time comes. Move an item into `docs/superpowers/specs/` (and then a
plan) before starting work on it.

## Offline → online posture for `/sondage` (and the broader app)

**Gap.** The survey loop today assumes online: `surveyClient.getNextItem`,
`submitRating`, `submitPairRating`, and Corriger all hit the network
synchronously. The "undo for sondage actions" spec
(2026-05-29) deliberately confines pending work to in-memory state
that's flushed on unmount — it does not introduce persistence, retries,
or a true offline queue. The same online-only assumption holds for the
rest of the app (grille, accueil, lobby), where a network drop today
surfaces only as a banner or error.

**Why later.** Solving offline well means: (1) a persistent client-side
work queue (likely IndexedDB) with idempotency keys, (2) server-side
semantics for replays (the survey API's partial-unique index already
helps for auth ratings; pair ratings and Corriger need an explicit
contract), (3) a UX for surfacing/retrying/discarding queued work,
(4) PWA install + service-worker shell so the SPA itself loads offline.
That is a multi-context workstream gated by an ADR — not something to
backdoor through a UI feature.

**Scope when picked up.**
- `frontend/src/application/` — durable submission-queue port and
  idempotency-key generation.
- `frontend/src/infrastructure/` — IndexedDB-backed adapter; service
  worker registration; offline-aware fetch wrapper.
- `frontend/src/ui/` — global online/offline indicator; per-action
  queued/synced affordances; the undo flow generalises into "discard
  while queued".
- `survey/api/openapi.yaml`, `grid/api/openapi.yaml`,
  `game/api/asyncapi.yaml` — explicit idempotency-key headers + replay
  semantics on all mutating endpoints.
- New ADR — durable client-side queues, conflict resolution, PWA
  posture, and the deliberate scope (which surfaces are
  "offline-write" vs "offline-read-only").

**Triggers to revisit.** Sustained user reports of vote loss on
flaky networks; PWA install becoming a product goal; a multi-context
RFC for resilient writes.

## SigNoz alert: stale campaign lock on `/sondage`

**Gap.** ADR-0059's operational risk note ("maintainer closes a
campaign and forgets to open the next one") is unguarded. Users would
see the lock banner indefinitely; the maintainer would only notice via
direct sondage traffic complaints.

**Why later.** The campaign-lock spec §11 explicitly listed this
alert as "recommended, not in v1 PR sequence". Designing it cleanly
requires picking between two query shapes — both need verification
against the survey-api's actual emitted metrics in SigNoz before
shipping (the 2026-05-21 SigNoz-alerts rollout cycled through 4 PRs
because the first author synthesized from partial docs instead of
fetching a known-working example).

**Shape A — gauge-based (cleanest).** Add a periodic gauge in
`survey-api` exposing `survey_campaign_open` (1 if `findOpen() != null`,
0 otherwise). PromQL: `min_over_time(survey_campaign_open[1h]) == 0`.
Adds ~10 lines of Kotlin + an instrumentation hook.

**Shape B — 423-rate proxy (no backend change).** Alert on sustained
`http_server_request_duration_seconds_count{http_response_status_code="423",service_name="survey-api"}`
above zero for >1 h. Imperfect: false-fires during legitimate lock
windows (acceptable since those are rare), and the exact metric name
depends on the OTel SDK version actually emitting it. Verify via
SigNoz's "Metrics Explorer" against a running survey-api before
writing the rule.

**Drop in.** `infra/observability/alerts/files/survey-campaign-lock-stale.json`
mirroring the existing `nats-consumer-lag-warning.json` v5
+ v2alpha1 schema. Notification channel: `alerts@wordsparrow.io`
(same as NATS alerts).

**Triggers to revisit.** Any time a sondage outage gets traced back
to a forgotten campaign-open.
