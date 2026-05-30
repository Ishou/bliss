# Survey rating undo — design

**Date:** 2026-05-30
**Context:** survey bounded context (ADR-0056), campaign lifecycle (ADR-0059)
**Status:** design — pending implementation plan

## Problem

The `/sondage` rating loop gives no way to retract a misclick. Verdicts are
one-key (J/K/L) so misclicks are easy, and a single submit can fan out to
several irreversible DB writes (a created proposed item, an in-place POS
mutation, an auto-GOOD rating, a progress increment). Today the card just
silently swaps to the next item.

This design adds a **persistent on-card undo affordance** backed by a
**server-side, durable, single-level undo** that reverses every write a submit
produced. The action log is built so multi-undo is a later UI-only addition.

## Decisions (locked with maintainer)

1. **Scope:** undo covers binary verdicts (GOOD/BAD), pair verdicts
   (LEFT_WINS/RIGHT_WINS/BOTH_GOOD/BOTH_BAD), and correctif submissions (text
   and POS-only). SKIP writes nothing server-side and is out of scope.
2. **Depth:** persisted action-log backend that *could* support multi-undo;
   v1 UI exposes single-level undo (the most recent action).
3. **Window — campaign-scoped, not wall-clock.** An action is undoable for as
   long as its **campaign is open**. Once the campaign closes, a fixed
   `CLOSE_GRACE = 8s` tail keeps the last actions undoable, then they are
   permanently settled. There is no per-action timer during an open campaign.
4. **Affordance — on the card, not a toast.** The undo control lives in the
   rating card (persistent), not a vanishing toast. Affordance lifetime equals
   undo lifetime by construction — no toast/window skew.
5. **Settling — by campaign status.** Exports and k-coverage retirement treat a
   rating as settled iff its campaign is closed past the grace. Because undo is
   campaign-long, an open campaign's ratings are *not* exportable until it
   closes (see "Settling" below for the export-cadence consequence).
6. **Anon:** undo works for everyone via a **capability token** returned by the
   submit response. Possession authorizes undo of anon actions; authed actions
   additionally bind to the session user.
7. **Expired undo:** `410 Gone` once the campaign-close grace lapses (distinct
   from 404 — expiry is not a secret given an unguessable token).
8. **Retention:** the action log is durable (1 row = 1 action, an audit trail).
   No aggressive prune; `user_id` is scrubbed on RGPD anonymization.
   Campaign-archival pruning is a future note, not in scope.

## Mechanism

Every submit, in **one transaction**:

1. Writes its rating/pair/item rows exactly as today.
2. Writes one `survey_actions` row carrying a **reversal recipe**, a fresh
   internal `action_id` (UUIDv7 PK), and the **hash** of a freshly minted
   capability token.
3. Returns the **plaintext capability token** in the response body (returned
   once, never stored in the clear).

The client keeps the token for the currently-displayed last action and renders
a persistent `Annuler` control on the card. `Annuler` calls
`POST /v1/actions/undo` with the token in the **body**, which reverses the
recipe in one transaction, stamps `undone_at`, and returns `204`. The client
re-shows the item/pair it just submitted from its own stashed state — no item
payload in the response, which keeps the contract symmetric across binary (one
item) and pair (two items) modes.

## Capability token — not UUIDv7, not in the URL

The token is **not** the `action_id`. It is a separate, cryptographically
random secret:

- **Generation:** `SecureRandom` → 32 bytes → base64url (~256 bits of entropy).
  *Not* a UUIDv7 — UUIDv7 carries only ~74 random bits and encodes creation
  time, so it is unfit as a bearer secret.
- **Transport:** request **body** (`{ "token": "..." }`), never the URL path.
  A path-borne secret leaks into access logs, proxy logs, and OTel spans, which
  would contradict the no-oracle posture.
- **At rest:** only `sha256(token)` is stored (`undo_token_hash`, unique). The
  server looks an action up by hashing the presented token. A DB read cannot
  recover a usable token.

`action_id` (UUIDv7) remains a perfectly good internal primary key — its
time-ordering helps index locality. It is never the capability.

## Data model — `survey_actions` (new, migration V8)

```sql
CREATE TABLE survey_actions (
    action_id          UUID PRIMARY KEY,                 -- UUIDv7; internal PK only, NOT the capability
    undo_token_hash    BYTEA NOT NULL UNIQUE,            -- sha256(capability token); lookup key for undo
    user_id            UUID,                             -- NULL for anon actions
    kind               TEXT NOT NULL CHECK (kind IN ('binary','pair','correctif')),
    campaign_id        UUID NOT NULL REFERENCES campaigns(campaign_id),
    created_at         TIMESTAMPTZ NOT NULL,             -- from injected Clock, not DB now()
    undone_at          TIMESTAMPTZ,                      -- set once; double-undo guard + idempotency
    -- reversal recipe (explicit typed columns, not opaque JSONB — queryable, auditable):
    created_rating_ids UUID[] NOT NULL DEFAULT '{}',     -- ratings rows to delete on undo
    created_pair_id    UUID,                             -- pair_ratings row to delete
    created_item_id    UUID,                             -- proposed item to delete IFF freshly inserted
    patched_item_id    UUID,                             -- item whose pos to restore (POS-only fix)
    prior_pos          TEXT,                             -- old pos value to restore
    prior_last_rated_at TIMESTAMPTZ                      -- user_progress.last_rated_at before the submit (NULL for anon)
);

CREATE INDEX survey_actions_campaign_idx ON survey_actions (campaign_id);
```

Key invariants the log exists to capture (not reconstructable from current DB
state at undo time):

- `created_item_id` is set **only** when `insertIfAbsent` actually inserted a
  new row — not when it reused an existing row on `(mot, definition)` conflict.
- `prior_pos` captures the pre-mutation POS that an in-place `updatePos` would
  otherwise destroy.
- `prior_last_rated_at` captures `user_progress.last_rated_at` *before* the
  submit's increment, so undo restores the exact prior cursor rather than
  guessing.

No `finalized_at` column and no `action_id` foreign key on `ratings` /
`pair_ratings`: settling is derived from the campaign's status via the
`campaign_id` those rows already carry (see "Settling").

## Authorization — capability, no oracle

`POST /v1/actions/undo` with `{ "token": "<base64url>" }`:

1. Hash the token; load action by `undo_token_hash`. Not found → **404**.
2. `action.user_id IS NOT NULL` (authed action): require
   `session.userId == action.user_id`, else **404** (not 403 — no existence
   leak; a leaked token cannot be undone by another user).
3. `action.user_id IS NULL` (anon action): possession of the token suffices →
   allow (caller may be anon or authed).
4. `undone_at IS NOT NULL` → **404** (already consumed, treat as gone).
5. Load the action's campaign:
   - open → undoable.
   - closed **and** `now <= closed_at + CLOSE_GRACE` → undoable.
   - closed **and** `now > closed_at + CLOSE_GRACE` → **410 Gone**.

`now` is the injected `Clock`. The token's ~256 random bits make enumeration
infeasible, so possession is a sound capability for anon.

## Reversal semantics

All reversal runs in a single transaction. Progress decrements mirror the
forward increments.

| Action kind | Forward writes | Undo |
|---|---|---|
| binary GOOD/BAD | 1 rating + progress++ | delete rating; progress-- ; restore `last_rated_at` |
| POS-only correctif | `updatePos(item)` + 1 rating(q=3) + progress++ | `updatePos(patched_item_id, prior_pos)`; delete rating; progress-- ; restore `last_rated_at` |
| text correctif | maybe-insert item + proposed_by + rating(q=3) + auto-GOOD rating + progress++ | delete both ratings; delete proposed_by; delete item **iff `created_item_id` set AND no other ratings reference it now**; progress-- ; restore `last_rated_at` |
| pair LEFT/RIGHT_WINS | 1 pair_rating + progress++ | delete pair_rating; progress-- ; restore `last_rated_at` |
| pair BOTH_GOOD/BOTH_BAD | 2 ratings + 2×progress++ | delete both ratings; 2×progress-- ; restore `last_rated_at` |

Notes:

- **"Delete item iff no other refs now"** guards the race where another user
  rated the proposed item while it was undoable — in that case the item and its
  foreign ratings stay; only the undoer's own rows are removed.
- **Progress** is restored cleanly: `items_rated` decrements with a
  `GREATEST(items_rated - n, 0)` underflow guard, and `last_rated_at` is set
  back to the stashed `prior_last_rated_at`. Anon actions (`user_id IS NULL`)
  touch no progress row.

## Transaction boundary (net-new architecture)

The survey infrastructure has **no transaction boundary today** — every repo
method opens its own `dataSource.connection.use {}`. A submit already fans out
to multiple non-atomic writes; undo's multi-row delete + POS restore + progress
restore makes atomicity mandatory (a partial undo corrupts state worse than a
partial submit).

Introduce:

- **`TransactionManager` port** (`application`) with
  `suspend fun <T> inTransaction(block: suspend () -> T): T`.
- **`PgTransactionManager` adapter** (`infrastructure`): opens one connection,
  `autoCommit = false`, binds it into the coroutine context, commits on success
  / rolls back on throw.
- The write repos on the submit and undo paths (`RatingRepository`,
  `PairRatingRepository`, `SurveyItemRepository`, `ProposedByRepository`,
  `UserProgressRepository`, `ActionLogRepository`) resolve the **ambient**
  connection from the coroutine context when present (a non-closing wrapper),
  else open a fresh pooled connection as today. Method signatures are
  unchanged; the change is the connection-acquisition helper.

`SubmitRatingUseCase` and `SubmitPairRatingUseCase` wrap their write sequence
(including the new action-row insert) in `inTransaction`. `UndoActionUseCase`
wraps its reversal in `inTransaction`.

## Settling — the clean-data guarantee (campaign-scoped)

A rating is **settled** (safe for consumers) iff its campaign is closed past the
grace. Two read paths gain a settling predicate keyed on the existing
`campaign_id` column + the injected clock passed as a parameter:

- **Export** (`PgRatingRepository.AGGREGATE_ALL_SQL` / `AGGREGATE_SINCE_SQL`):
  join `campaigns` and add `AND c.status = 'CLOSED' AND c.closed_at < :cutoff`
  where `:cutoff = clock.now() - CLOSE_GRACE`.
- **Retirement** (`PgSurveyItemRepository.SATURATED_BY_TIER_SQL` count
  subquery): **does not** gain the settling filter — k-coverage must count
  open-campaign ratings or items would never retire mid-campaign. Retirement
  therefore tolerates a rare undo leaving an item retired at k−1; an undo does
  **not** un-retire. This is the one place undo is not perfectly clean, and it
  is acceptable (undo is rare; a slightly-early retirement costs nothing).

**Export-cadence consequence (call out for the pipeline owner):** because undo
is campaign-long, an open campaign's ratings are excluded from export until the
campaign closes + grace. The incremental `aggregateForExport(since)` therefore
emits only already-closed campaigns' rows — export is effectively
**per-campaign-at-close**, not continuous mid-campaign. With the
single-active-campaign invariant (ADR-0059 `findOpen()` returns one), the open
campaign's rows are always the newest, so the `created_at` watermark never
advances into un-settled rows — the existing cursor stays correct. If
concurrent campaigns are ever introduced, switch the export cursor to a
campaign-scoped one.

## Single clock

All timestamps written (`created_at`, `undone_at`) and all comparisons (the
close-grace check, the export `:cutoff`) use the injected `Clock` port. No path
uses DB `now()`. This keeps the close-grace boundary and the settling boundary
on one clock and makes both fakeable in tests.

## API surface (schema-first, ADR-0003)

Schema PR merges first; then `pnpm api:generate` regenerates
`frontend/src/infrastructure/api/...` (survey types) and the diff is committed.

- **Additive:** `undoToken: { type: string, nullable: true }` on
  `RatingResponse` and `PairRatingResponse`. Non-null on `201` (a fresh action);
  **null on `409`** (idempotent re-rate — no action created, nothing to undo);
  null on `204` pair SKIP (no body anyway).
- **New endpoint:** `POST /v1/actions/undo`
  - request body: `{ "token": "<base64url>" }`.
  - `204` → reversed successfully (no body; client restores its stashed item/pair).
  - `404` → not found / not yours / already undone (ProblemDetails).
  - `410` → campaign-close grace expired (ProblemDetails).

The capability token is in the body, not the path, by design (see "Capability
token").

## UI (frontend, ADR-0002 / ADR-0050)

- `sondage.lazy.tsx` and `sondage.pairs.lazy.tsx`: on submit success, capture
  `undoToken` and stash the just-submitted item (binary) / pair (pairs route)
  **before** advancing to the next item.
- Render a **persistent on-card** `Annuler` control (not a toast) whenever a
  stashed undoable action exists. French copy, e.g. `Annuler la dernière note`.
  Single-slot: a new rating supersedes the prior undo affordance (consistent
  with single-level undo).
- `Annuler` → `surveyClient.undoAction(token)`; on `204` re-show the stashed
  item/pair and clear the affordance; fire `survey/verdict_undone` analytics.
  On `410`/`404` clear the affordance (optional "trop tard" flash).
- `Annuler` reachable by keyboard; respects the lock — the card is already
  disabled when the campaign is locked, but the undo control still fires during
  the close grace.
- No `ToastProvider` dependency for this feature (the affordance is in the
  card); if a toast is later wanted for confirmation, destructure
  `const { show } = useToast()` to avoid the unstable-reference effect-retrigger
  (frontend skill).

## Errors / status summary

| Situation | Status |
|---|---|
| undo ok | 204 |
| token not found / not yours / already undone | 404 |
| campaign-close grace expired | 410 |
| (submit) auth already rated | 409, `undoToken: null` |

## Ports / use cases (hexagonal)

- New `ActionLogRepository` port (insert recipe + token hash, find by token
  hash, mark undone, scrub `user_id` for anonymization) + `PgActionLogRepository`
  adapter.
- New `TransactionManager` port + `PgTransactionManager` adapter (see
  "Transaction boundary").
- New `UndoActionUseCase` (hash token → load → authorize → campaign-grace check
  → reverse in txn → mark undone). Returns a sealed result mapped to
  204/404/410.
- `UserProgressRepository` gains `decrementItemsRated(userId, priorLastRatedAt)`
  to mirror `incrementItemsRated` (binary/correctif undo decrements once; pair
  BOTH_GOOD/BOTH_BAD undo decrements twice), restoring `last_rated_at` from the
  stashed value.
- `SubmitRatingUseCase` / `SubmitPairRatingUseCase` extended to mint the token,
  assemble the reversal recipe (they already know `created_item_id`
  freshly-created vs reused, and `prior_pos`; they read `prior_last_rated_at`
  before incrementing), write the action row, and return the token — all inside
  `inTransaction`.
- `AnonymizeUserRatingsUseCase` extended to null `survey_actions.user_id` for
  the erased user.

## Testing

- **Domain/usecase:** reversal per kind; "item has other refs → keep item"
  branch; double-undo idempotency (second undo → 404); campaign-close grace
  boundary (undoable at +7s, 410 at +9s) with a fake clock; auth binding (wrong
  user → 404); anon possession path; progress decrement + `last_rated_at`
  restore + underflow guard.
- **Persistence (testcontainer):** transactional multi-row delete + POS restore
  + progress restore (all-or-nothing on injected failure); token-hash lookup;
  settling filter excludes open-campaign rows from the export aggregate and
  includes them once the campaign closes past grace; retirement count still sees
  open-campaign rows.
- **Route:** 204/404/410 mapping; token-in-body (not path); capability
  semantics; additive `undoToken`.
- **Frontend:** persistent on-card `Annuler` renders after a submit; undo
  re-shows the stashed item; anon path works without a session; affordance
  clears on 404/410.

## ADR / registry impact

Touches `survey/**/persistence/**`, `survey/**/usecases/**`, and
`survey/api/openapi.yaml` — governed by ADR-0059 (campaign lifecycle, which owns
the rating-POST lock) and ADR-0056. Two decisions deserve recording as an
**amendment to ADR-0059** rather than a new ADR: (a) undo is undoable for the
campaign's open lifetime + an 8s close grace, and (b) export settles
per-campaign-at-close as a consequence. Add the new use-case path(s) to
`docs/adr/INDEX.md` in the same PR (`registry-coherence` gate).

## Out of scope

- Multi-undo UI (the backend supports it per-token; no UI in v1).
- Undo of SKIP (no server write to reverse).
- Campaign-archival pruning of the action log.
- Token persistence across page reloads (the on-card affordance is per session;
  a reload abandons the pending undo, which is fine for single-level on-card).

## PR sequencing

1. **Schema-only PR:** openapi `undoToken` additive field + `POST /v1/actions/undo`.
2. **Backend PR:** V8 migration (`survey_actions`), `TransactionManager` +
   `ActionLogRepository` + adapters, `UndoActionUseCase`, submit use-cases mint
   token + write recipe inside a transaction, export settling filter,
   `decrementItemsRated`, ADR-0059 amendment + INDEX.md.
3. **Frontend PR:** regenerate types, persistent on-card `Annuler` wiring on
   both sondage routes, stash-and-restore, analytics, tests.
