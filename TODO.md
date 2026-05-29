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
