# Spec: `survey/` — campaign-bounded rating sessions with lock

**Date**: 2026-05-29
**Status**: Design — awaiting approval. Implementation starts only after ADR-0059 is accepted and the schema-only PR has merged.
**Workstream owner**: Ishou
**Companion ADR**: ADR-0059 (to be drafted; merges before any code).

---

## 1. Why

Today the survey context collects ratings continuously: the `/sondage` route accepts verdicts and Corriger submissions whenever the API is up, and an export job converts whatever has accumulated into a CSV consumed by `bliss-clue-ai`'s training pipeline (per ADR-0056 §6). There is no first-class notion of "the data window for training batch N." The maintainer cannot pause data collection while a batch is being exported, frozen, or trained on; ratings landing during that window mix into the next campaign with no provenance.

This spec introduces **campaigns** as the rating-collection lifecycle: each rating belongs to exactly one campaign, the maintainer can close the current campaign before exporting for training, and the `/sondage` route enters a locked read-only state during the closed window. The lock is a clean cut: while closed, no new ratings or pair-ratings can be created; the frontend renders a banner explaining the pause; the underlying item-preview behaviour stays so users can see what is coming.

This is the **first half** of a two-part workstream. The second half — post-commit undo of submitted ratings within an 8 s grace window — is deferred to a follow-up spec. The data model and API shape introduced here are deliberately compatible with that follow-up (see §10).

Rationale and constraints inherited from existing project artifacts:

- **CLAUDE.md**: schema-first workflow, 400-line PR cap, `<type>(<bounded-context>-<layer>)` commit scope, ADR before non-trivial change, expand-and-contract migrations, registries cannot lag.
- **MANIFESTO.md / ADR-0001 §6a**: implementer ≠ reviewer; TDD for domain logic; no mocks of own code.
- **ADR-0001 §3 / ADR-0003**: schema-only PR first; producer and consumer PRs land after the schema merges.
- **ADR-0002**: hexagonal layers in `frontend/`; ESLint boundaries forbid `ui` → `infrastructure` direct imports, but allow `ui` → `application`.
- **ADR-0056**: survey bounded context, rating model (ratings, pair_ratings, rater_proposed survey_items), §8.1 export contract with `bliss-clue-ai`.
- **ADR-0050**: a11y baseline — `pnpm a11y` must stay green.

## 2. Decision summary

Introduce a **`campaigns`** table in the survey schema with `(id, batch_label, opened_at, closed_at)`. At most one open campaign at any time, enforced by a partial unique index. Both `ratings` and `pair_ratings` gain a nullable `campaign_id` FK; the server stamps the current open campaign's id at insert time. When the current campaign is closed (`closed_at IS NOT NULL`), all rating POSTs return **423 Locked**.

Campaign open and close are performed by **direct SQL** in v1 (`INSERT` for open, `UPDATE` for close). No HTTP admin endpoints ship in v1 — the maintainer-auth question is intentionally deferred. The 1-line SQL verbs are documented in the ADR and reproduced verbatim in the project runbook.

The frontend learns campaign status from a new `GET /v1/campaign/current` endpoint. The `/sondage` and `/sondage/pairs` routes render a `LockBanner` and disable verdict/Corriger controls when `closedAt IS NOT NULL`. `GET /v1/items/next` continues to serve items so the user can still see what is queued; only the action buttons are gated.

Historical ratings (before this change) are stamped with retro campaign ids by a **separate one-shot backfill script** that reads Modal logs and timestamps to attribute each row to the right batch. Migration sequence is expand-and-contract: nullable column first, backfill, then a follow-up migration tightens `campaign_id NOT NULL` on new rows.

**Out of scope, deferred to the undo spec**: `submission_id`, `withdrawn_at`, `survey_items.proposed_by_rating_id`, `DELETE /v1/submissions/{id}`, the 8 s grace window, the post-commit-undo hook, the Toast action slot, the restore-prior-card UX, SKIP-undo.

## 3. Architecture

No new modules. Changes are confined to existing layers of `survey/` and `frontend/`.

```
survey/
  domain/          # + Campaign aggregate (CampaignId, BatchLabel, opened_at, closed_at)
                   #   pure data class; no framework deps.
  application/     # + CampaignRepository port
                   # + GetCurrentCampaignUseCase
                   # SubmitRatingUseCase / SubmitPairRatingUseCase:
                   #   - resolve current campaign once at the top of execute()
                   #   - if closed (or absent), return Locked result
                   #   - otherwise stamp campaign_id on the inserted row
  infrastructure/  # + CampaignsRepository (Flyway migration adds the table)
                   # + alter ratings, pair_ratings: campaign_id BIGINT NULL FK
  api/             # + GET /v1/campaign/current → 200 { id, batchLabel, openedAt, closedAt|null }
                   # POST /v1/items/{id}/rating → adds 423 response
                   # POST /v1/pair-ratings       → adds 423 response
                   # Both 201 responses now include campaignId
  worker/          # unchanged in v1.
                   # The export job will eventually filter by campaign_id; that join is
                   # not in this spec because the existing export path remains usable
                   # against the legacy backfilled ids.

frontend/
  infrastructure/api/survey/client.ts   # + getCurrentCampaign()
  infrastructure/api/survey/types.ts    # regenerated from openapi.yaml
  application/survey/types.ts           # + Campaign type
  application/survey/index.ts           # re-export Campaign
  ui/components/sondage/useCampaignStatus.ts  # NEW — fetches on mount, refreshes
                                              # on visibilitychange and on any 423.
                                              # Co-located per repo convention
                                              # (see ui/components/grid/useGridNavigation.ts
                                              # and similar for the pattern).
  ui/components/sondage/LockBanner.tsx  # NEW — renders the closed-state message
  ui/components/sondage/labels.ts       # + lock-banner copy
  ui/components/sondage/RatingCard.tsx  # disabled prop wires through to all action buttons
  ui/components/sondage/PairCard.tsx    # disabled prop wires through to all action buttons
  ui/routes/sondage.lazy.tsx            # use useCampaignStatus; show LockBanner;
                                        # treat 423 from submitRating as lock signal
  ui/routes/sondage.pairs.lazy.tsx      # same pattern for pair flow

scripts/survey/
  backfill_campaigns.py                 # NEW — reads Modal logs + ratings timestamps,
                                        # stamps campaign_id on historical rows in batches.
                                        # Idempotent (skips rows already stamped).
```

**Cross-context edges**: none change. No new NATS subjects, no new HTTP calls into other contexts.

## 4. Data model

### 4.1 New table

```sql
CREATE TABLE campaigns (
  campaign_id UUID        PRIMARY KEY,
  batch_label TEXT        NOT NULL,
  opened_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  closed_at   TIMESTAMPTZ NULL
);

-- At most one open campaign at any time.
CREATE UNIQUE INDEX campaigns_one_open
  ON campaigns ((1)) WHERE closed_at IS NULL;

-- Cheap fallback for findCurrent() when no open row exists.
CREATE INDEX campaigns_opened_at_idx
  ON campaigns (opened_at DESC);
```

UUID primary key matches the convention established by `survey_items`, `ratings`, `pair_ratings`, and `user_progress`. The application generates the id via the existing `IdGenerator` port — same pattern as ratings.

`batch_label` is a short human string (e.g. `round-7`, `pos-pass-2`) chosen by the maintainer at open time. It carries no enforced format; it is the audit-trail label that ties a campaign to its downstream training batch in `bliss-clue-ai`.

The partial unique index `campaigns_one_open` is the load-bearing invariant: the application code reads "the current campaign" as `SELECT id FROM campaigns WHERE closed_at IS NULL` and relies on the index to guarantee at most one row.

### 4.2 Column additions

```sql
ALTER TABLE ratings
  ADD COLUMN campaign_id UUID NULL REFERENCES campaigns(campaign_id);
CREATE INDEX ratings_campaign_idx ON ratings (campaign_id);

ALTER TABLE pair_ratings
  ADD COLUMN campaign_id UUID NULL REFERENCES campaigns(campaign_id);
CREATE INDEX pair_ratings_campaign_idx ON pair_ratings (campaign_id);
```

`campaign_id` is nullable in this spec's migration. New rows inserted by the application after deploy will have it populated by the use case; legacy rows are stamped by the backfill script (§7). A follow-up migration (out of scope here) will tighten to `NOT NULL` once the backfill is verified.

### 4.3 What does NOT change in v1

- `survey_items` is untouched. Corriger-created `rater_proposed` items inherit their campaign via the parent `ratings.campaign_id` row. The eventual export query joins through.
- No `withdrawn_at`, no `submission_id`, no `proposed_by_rating_id`. Those land with the undo spec.

## 5. API surface

Schema-only PR first per ADR-0001 §3.

### 5.1 New endpoint

```yaml
/v1/campaign/current:
  get:
    operationId: getCurrentCampaign
    summary: Return the current campaign and its lock state.
    tags: [campaigns]
    responses:
      '200':
        description: Campaign state. `closedAt` non-null means rating writes are rejected.
        content:
          application/json:
            schema:
              type: object
              required: [campaignId, batchLabel, openedAt, closedAt]
              properties:
                campaignId: { type: string, format: uuid }
                batchLabel: { type: string, maxLength: 64 }
                openedAt:   { type: string, format: date-time }
                closedAt:   { type: [string, "null"], format: date-time }
      '503':
        $ref: '#/components/responses/ProblemDetails'
```

There is no separate "no current campaign" response shape: when the maintainer has closed a campaign and not yet opened the next one, the most recent row (with `closedAt` set) is returned. The frontend treats that as locked. This avoids a tri-state on the client.

### 5.2 Modified endpoints

```yaml
/v1/items/{itemId}/rating:
  post:
    # ... existing parameters and request body unchanged ...
    responses:
      '201':
        description: Rating recorded.
        content:
          application/json:
            schema:
              type: object
              required: [submittedAs, campaignId]
              properties:
                submittedAs: { type: string, enum: [auth, anon] }
                campaignId:  { type: string, format: uuid }
      '423':
        description: No open campaign — the sondage is locked.
        content:
          application/problem+json:
            schema: { $ref: '#/components/schemas/Problem' }

/v1/pair-ratings:
  post:
    # ... existing parameters and request body unchanged ...
    responses:
      '201':
        description: Pair rating recorded.
        content:
          application/json:
            schema:
              type: object
              required: [campaignId]
              properties:
                campaignId: { type: string, format: uuid }
      '423':
        description: No open campaign — the sondage is locked.
        content:
          application/problem+json:
            schema: { $ref: '#/components/schemas/Problem' }
```

The 201 response gains `campaignId` for symmetry and as forward-compatibility for the undo spec (which will need it to know which campaign a withdrawal targets). The frontend does not need to surface this id in v1.

### 5.3 What does NOT change

- `GET /v1/items/next` and `GET /v1/items/pairs/next` are untouched. They keep serving items regardless of lock state. The frontend renders the preview but disables the action buttons when locked. This is the cheapest path to the "see what's coming" affordance.
- The `correctif` payload on rating POST is untouched. When locked, the entire POST returns 423 — there is no "partial correctif allowed" mode.

### 5.4 Admin verbs (SQL-only in v1)

These are the operations a maintainer runs from psql when prepping or finishing a training batch. They are documented in the ADR and in the survey-context runbook; no HTTP wrappers ship in v1.

```sql
-- Open a new campaign. Fails if one is already open (campaigns_one_open).
-- requires pg_uuidv7; or generate externally: python3 -c 'import uuid_utils; print(uuid_utils.uuid7())'
INSERT INTO campaigns (campaign_id, batch_label)
     VALUES (uuidv7(), 'round-7')
  RETURNING campaign_id, opened_at;

-- Close the currently open campaign. Returns the closed row's id for audit.
UPDATE campaigns
   SET closed_at = now()
 WHERE closed_at IS NULL
RETURNING campaign_id, batch_label, closed_at;
```

Both statements are single-line, deterministic, and produce a row that the maintainer can paste into the runbook log. Wrapping these in HTTP later is a small, additive change.

## 6. Backend flow

### 6.1 Use cases

`SubmitRatingUseCase.execute(cmd)` gains a single new step at the top:

```kotlin
val campaign = campaigns.findOpen() ?: return SubmitRatingResult.Locked
// existing flow continues; the inserted Rating(...) gets `campaignId = campaign.id`
```

`SubmitPairRatingUseCase.execute(cmd)` gets the same gate, applied to both the pair-row insert and the absolute-row inserts that BOTH_GOOD / BOTH_BAD produce. The campaign id is stamped on every row written in this transaction.

`Locked` is a new `sealed interface` arm of `SubmitRatingResult` / `SubmitPairRatingResult`, mapped to HTTP 423 at the API edge.

### 6.2 Repository

`CampaignRepository` port (application layer):

```kotlin
interface CampaignRepository {
    suspend fun findOpen(): Campaign?
    suspend fun findCurrent(): Campaign?   // open one if any, else the most recent closed
}
```

The Postgres adapter implements `findOpen` as `SELECT ... WHERE closed_at IS NULL LIMIT 1` (the partial unique index covers this) and `findCurrent` as `findOpen() ?: SELECT ... ORDER BY opened_at DESC LIMIT 1` (covered by `campaigns_opened_at_idx`).

`findCurrent` is the read used by `GET /v1/campaign/current`. The endpoint always returns a row in the steady state; only a brand-new database (no campaigns ever opened) returns a 503 — and that is an operator error caught by deploy-time smoke tests, not a runtime concern.

### 6.3 Lock semantics under race

When the maintainer closes a campaign at time T, the `UPDATE` is transactional. Any rating-insert transaction that holds a row lock on the open campaign row at the time of close will either:

- Commit before the close — its row gets `campaign_id = the now-closed campaign`. The rating is in the batch. Acceptable: this rating arrived before the close visibly took effect.
- Be invalidated by the close — `findOpen()` re-read inside the transaction returns null, the use case returns `Locked`, the rating is rejected with 423. Acceptable: the user sees the lock banner on next page interaction.

No "grace window" exists in v1. The hard cut is the simplest and most defensible semantic; users may occasionally see a "Vote refusé — campagne en pause" inline error if they click the moment after a close, which the LockBanner immediately explains.

## 7. Backfill

A one-shot Python script at `scripts/survey/backfill_campaigns.py` performs the historical stamping.

**Input**:
- Modal job logs (already mounted in `modal_jobs/`) — each training batch run has a timestamp window.
- The current `ratings` and `pair_ratings` tables.

**Output**: each historical row gets `campaign_id` set to a backfilled campaign row.

**Algorithm**:

1. Read Modal logs; produce a list of `(batch_label, opened_at, closed_at)` tuples — one per historical batch. These become the seed campaign rows.
2. `INSERT` them all in a single transaction, with explicit `closed_at` non-null (every historical campaign is closed by definition).
3. For each historical campaign, `UPDATE ratings SET campaign_id = $1 WHERE created_at >= $2 AND created_at < $3 AND campaign_id IS NULL` (idempotent — only stamps unstamped rows).
4. Same for `pair_ratings`.
5. Print a coverage report: how many rows were stamped per campaign, how many ratings remain `campaign_id IS NULL` (these are pre-Modal or otherwise unattributed — surface as a manual review queue).

**Operational notes**: the script is run **after** the schema migration that adds the nullable column. It is **not** part of any Flyway migration — keeping it out of the migration path means a bug in log parsing cannot wedge the cluster. The follow-up migration that tightens to `campaign_id NOT NULL` runs only after manual review confirms 100% coverage (or after the unattributed rows are pruned).

## 8. Frontend flow

### 8.1 Status hook

```ts
// ui/components/sondage/useCampaignStatus.ts
type CampaignStatus =
  | { kind: 'loading' }
  | { kind: 'open';   campaign: Campaign }
  | { kind: 'closed'; campaign: Campaign };

function useCampaignStatus(client: SurveyClient): {
  status: CampaignStatus;
  refresh: () => void;
};
```

Behaviour:
- Calls `client.getCurrentCampaign()` on mount.
- Re-fetches on `document.visibilitychange` (transitioning to visible).
- Exposes `refresh()` so the route can re-fetch immediately when a POST returns 423 (defence in depth: the local status is stale, refresh closes the gap).
- No periodic polling. Maintainers close a campaign at human cadence; visibility-driven refresh is enough.

### 8.2 LockBanner

```tsx
// ui/components/sondage/LockBanner.tsx
function LockBanner({ campaign }: { readonly campaign: Campaign }) { ... }
```

Renders a `role="status"` banner at the top of the article element on `/sondage` and `/sondage/pairs`. Copy (in `labels.ts`):

> Campagne en pause — un nouveau lot est en cours d'entraînement. Revenez bientôt pour la suite.

Visual posture follows the existing `ConnectionBanner` info-tone styling (surface bg, fgMuted text) — not error-tone, because this is a planned operational state, not a failure.

### 8.3 Route wiring

`sondage.lazy.tsx` and `sondage.pairs.lazy.tsx` each:

1. Call `useCampaignStatus(surveyClient)` once.
2. If `status.kind === 'closed'`, render the `LockBanner` above the existing intro paragraph. Item-loading flow stays the same; the RatingCard/PairCard renders with a new `disabled` prop set to `true`.
3. Catch `423` from `submitRating` / `submitPairRating` / Corriger — same response: call `refresh()` on the hook, render the LockBanner. No inline error needed; the banner is the explanation.

### 8.4 Card disabled prop

`RatingCard` and `PairCard` gain a `readonly disabled?: boolean` prop. When true:
- All verdict buttons render with `aria-disabled="true"` and ignore clicks.
- The "Corriger" entry button on `RatingCard` is hidden; if a Corriger panel is already open when the lock arrives, it closes and any in-progress text is dropped (in v1; undo of an in-progress edit is not in scope).
- The card still shows the item content so the user sees what is queued.

`disabled` is the only change to these components' public API. Their existing tests get one new case each (axe-clean disabled state).

## 9. Testing

### 9.1 Backend

- **`SubmitRatingUseCase`**: new test case "rejects when no open campaign" returns `Locked` without inserting; new test case "stamps campaign_id of open campaign on accepted insert" verified via in-memory repository.
- **`SubmitPairRatingUseCase`**: same two cases, plus "BOTH_GOOD stamps both absolute rows with the same campaign_id."
- **`CampaignsRepositoryPostgresTest`** (Testcontainers): `findOpen` returns null when only closed campaigns exist; `findOpen` returns the lone open row; partial-unique-index violation when attempting to insert a second open row; `findCurrent` falls back to most-recent-closed.
- **Property-based**: for any sequence of insert/close operations, the invariant `count(WHERE closed_at IS NULL) ≤ 1` always holds. (Constraint-based, but property-based test catches regressions if someone removes the partial unique index.)
- **API contract**: 423 response payload conforms to `Problem`.

### 9.2 Frontend

- **`useCampaignStatus`** unit test (Vitest + MSW): mount → fetches; visibility change → refetches; refresh() → refetches.
- **`/sondage` route test** (Vitest + MSW): when campaign is closed, LockBanner renders; verdict buttons have `aria-disabled="true"`; clicking does not POST.
- **`/sondage` route test**: when POST returns 423, refresh fires and LockBanner appears.
- **Same suite for `/sondage/pairs`**.
- **a11y**: `pnpm a11y` covers the closed state — disabled buttons must remain reachable in tab order and announce their disabled state.

### 9.3 End-to-end smoke (manual, post-deploy)

1. Open psql, run the open-campaign INSERT.
2. Hit `/sondage`, verify card renders and GOOD/BAD submit lands.
3. Run the close-campaign UPDATE.
4. Reload `/sondage`, verify LockBanner renders and buttons are disabled.
5. Run a fresh open-campaign INSERT (new `batch_label`).
6. Reload, verify the page is interactive again.

## 10. Forward compatibility with the undo spec

This spec is intentionally compatible with the deferred undo work. When the undo spec lands it will:

- Add `submission_id UUID` to `ratings` and `pair_ratings`. The campaign_id stamping introduced here is unchanged; submission_id is just an additional column populated at insert.
- Add `withdrawn_at TIMESTAMPTZ NULL` to `ratings`, `pair_ratings`, and `survey_items`. All reads in the export path will gain a `WHERE withdrawn_at IS NULL` clause.
- Introduce an 8 s grace concept: the campaign's `closed_at` will continue to block new inserts; an additional check `now < closed_at + interval '8 seconds'` will gate the DELETE/withdraw path.
- Add `DELETE /v1/submissions/{submissionId}` and the `usePostCommitUndo` hook.

Nothing in v1 forecloses any of these. In particular, the 201-response `campaignId` is forward-compatible with the future `submissionId` (additive, not replacing).

## 11. Operational notes

- **Deploy order**: schema PR → backend PR → frontend PR → backfill script PR. The backfill runs once, post-deploy, by the maintainer. A staging dry-run is mandatory before prod.
- **Rollback**: the migration is additive; rollback is "drop the index, drop the column" (Flyway down). No data loss because no production read path depends on `campaign_id` being non-null in v1.
- **Observability** (recommended, not in v1 PR sequence): a SigNoz alert that fires when `findOpen()` returns null for more than 1 hour while rating-submit traffic is hitting the API would catch "the maintainer closed a campaign and forgot to open the next one." Symptom-based per CLAUDE.md; deferrable to a follow-up.
- **Runbook**: a runbook section — appended to `docs/deploy.md` or a new file under `survey/` — documents the two SQL statements and their expected output, alongside the existing operational notes for the survey context.

## 12. PR decomposition

Each PR stays under the 400-line cap; cap-override invoked only if a single workstream genuinely needs it.

1. **ADR-0059** — campaign-bounded survey rating sessions. Documents the lifecycle, the partial-unique invariant, the 1-line SQL admin verbs, and the deliberate exclusion of undo. Updates `docs/adr/INDEX.md`. Merges first.
2. **Schema-only PR** — `survey/api/openapi.yaml` adds `GET /v1/campaign/current`, modifies `POST /v1/items/{id}/rating` and `POST /v1/pair-ratings` responses (201 gains `campaignId`, new 423). Triggers frontend's `openapi-typescript-drift` regen on the next consumer PR. CI gate: `openapi-lint`.
3. **Backend PR** — Flyway migration (`campaigns` table + column additions + indexes); `Campaign` domain type; `CampaignRepository` port + Postgres adapter (Testcontainers test); `SubmitRatingUseCase` and `SubmitPairRatingUseCase` Locked branches; `GET /v1/campaign/current` handler; 423 mapping at the API edge. Tests as listed in §9.1.
4. **Frontend PR** — `getCurrentCampaign()` on the API client; regenerated types; `Campaign` re-export in `application/survey`; `useCampaignStatus`; `LockBanner`; `RatingCard`/`PairCard` disabled prop; route wiring on `/sondage` and `/sondage/pairs`. Tests as listed in §9.2.
5. **Backfill script PR** — `scripts/survey/backfill_campaigns.py` + a README section under `scripts/survey/` describing inputs, idempotency, and the coverage report.

Optionally, after the four-PR sequence has been stable for a week, a small follow-up PR tightens `campaign_id` to `NOT NULL` on new rows.
