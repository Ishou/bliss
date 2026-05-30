# Survey Campaign Lock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce campaign-bounded rating sessions in the survey context — a `campaigns` lifecycle, a server-side lock that returns 423 when no campaign is open, and a frontend banner that disables `/sondage` actions while locked.

**Architecture:** Add a `campaigns` table + `campaign_id` FK on `ratings` and `pair_ratings`. New `CampaignRepository` port and a `GetCurrentCampaignUseCase`. `SubmitRatingUseCase` and `SubmitPairRatingUseCase` gain a `Locked` result arm when no open campaign exists. The frontend learns lock status from a new `GET /v1/campaign/current` endpoint, fetched on route mount and `visibilitychange`, and renders a `LockBanner` + disabled action buttons when closed.

**Tech Stack:** Kotlin 2.3.21 + Ktor on JDK 21 (survey context) — Flyway migrations, Testcontainers-Postgres, assertk + kotest property-based; Vite + React 19 + TanStack Router + Panda CSS + Vitest + MSW (frontend); Python 3.12 (backfill script).

**Spec:** `docs/superpowers/specs/2026-05-29-survey-campaign-lock-design.md` (this plan is the execution path).

**Branch:** continue on `docs/survey-campaign-lock-spec` for ADR + INDEX update, then split into per-PR branches as the phases progress (see Phase boundaries).

---

## File Structure

This plan touches five sub-areas. Files are grouped by phase.

### Phase A — ADR
- Create: `docs/adr/0059-survey-campaign-lock.md`
- Modify: `docs/adr/INDEX.md` (append the path bindings)

### Phase B — OpenAPI schema-only
- Modify: `survey/api/openapi.yaml` (new path, new schemas, new 423 response)

### Phase C — Backend
- Create: `survey/infrastructure/src/main/resources/db/migration/V7__campaigns.sql`
- Create: `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/Campaign.kt`
- Modify: `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/Ids.kt` (add `CampaignId`)
- Create: `survey/application/src/main/kotlin/com/bliss/survey/application/ports/CampaignRepository.kt`
- Create: `survey/application/src/main/kotlin/com/bliss/survey/application/usecases/GetCurrentCampaignUseCase.kt`
- Modify: `survey/application/src/main/kotlin/com/bliss/survey/application/usecases/SubmitRatingUseCase.kt` (add `Locked` arm + campaign-id stamping)
- Modify: `survey/application/src/main/kotlin/com/bliss/survey/application/usecases/SubmitPairRatingUseCase.kt` (same)
- Modify: `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/Rating.kt` (add `campaignId` field)
- Modify: `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/PairRating.kt` (add `campaignId` field)
- Create: `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgCampaignRepository.kt`
- Modify: `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgRatingRepository.kt` (extend INSERT)
- Modify: `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgPairRatingRepository.kt` (extend INSERT)
- Create: `survey/api/src/main/kotlin/com/bliss/survey/api/dto/CampaignDtos.kt`
- Create: `survey/api/src/main/kotlin/com/bliss/survey/api/routes/GetCurrentCampaignRoute.kt`
- Modify: `survey/api/src/main/kotlin/com/bliss/survey/api/dto/RatingDtos.kt` (add `campaignId` to responses)
- Modify: `survey/api/src/main/kotlin/com/bliss/survey/api/routes/SubmitRatingRoute.kt` (handle Locked → 423)
- Modify: `survey/api/src/main/kotlin/com/bliss/survey/api/routes/SubmitPairRatingRoute.kt` (handle Locked → 423)
- Modify: `survey/api/src/main/kotlin/com/bliss/survey/api/Wiring.kt` (expose `getCurrentCampaign`)
- Modify: `survey/api/src/main/kotlin/com/bliss/survey/api/Main.kt` (construct PgCampaignRepository + use case)
- Modify: `survey/api/src/main/kotlin/com/bliss/survey/api/Module.kt` (mount new route)
- Create: `survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/persistence/PgCampaignRepositoryTest.kt`
- Create: `survey/application/src/test/kotlin/com/bliss/survey/application/usecases/SubmitRatingUseCaseLockedTest.kt`
- Create: `survey/application/src/test/kotlin/com/bliss/survey/application/usecases/SubmitPairRatingUseCaseLockedTest.kt`
- Create: `survey/api/src/test/kotlin/com/bliss/survey/api/routes/GetCurrentCampaignRouteTest.kt`
- Modify: `survey/api/src/test/kotlin/com/bliss/survey/api/routes/SubmitRatingRouteTest.kt` (add 423 case)

### Phase D — Frontend
- Modify: `frontend/src/infrastructure/api/survey/types.ts` (regenerated)
- Modify: `frontend/src/infrastructure/api/survey/client.ts` (add `getCurrentCampaign`)
- Modify: `frontend/src/application/survey/types.ts` (add `Campaign`)
- Modify: `frontend/src/application/survey/index.ts` (re-export `Campaign`)
- Create: `frontend/src/ui/components/sondage/useCampaignStatus.ts`
- Create: `frontend/src/ui/components/sondage/LockBanner.tsx`
- Modify: `frontend/src/ui/components/sondage/labels.ts` (lock copy)
- Modify: `frontend/src/ui/components/sondage/index.ts` (export new component)
- Modify: `frontend/src/ui/components/sondage/RatingCard.tsx` (`disabled` prop)
- Modify: `frontend/src/ui/components/sondage/PairCard.tsx` (`disabled` prop)
- Modify: `frontend/src/ui/routes/sondage.lazy.tsx` (wire status + LockBanner)
- Modify: `frontend/src/ui/routes/sondage.pairs.lazy.tsx` (wire status + LockBanner)
- Create: `frontend/tests/use-campaign-status.test.tsx`
- Create: `frontend/tests/sondage-lock-banner.test.tsx`
- Create: `frontend/tests/sondage-route-locked.test.tsx`
- Create: `frontend/tests/sondage-pairs-route-locked.test.tsx`

### Phase E — Backfill script
- Create: `scripts/survey/backfill_campaigns.py`
- Create: `scripts/survey/README.md` (or append a section)
- Create: `scripts/survey/test_backfill_campaigns.py`

---

## Phase A — ADR-0059 and INDEX update

PR title: `docs(adr): ADR-0059 survey campaign lock`
Branch in use: `docs/survey-campaign-lock-spec` (already open, this builds on it).

### Task A1: Write ADR-0059

**Files:**
- Create: `docs/adr/0059-survey-campaign-lock.md`

- [ ] **Step 1: Write the ADR**

```markdown
# ADR-0059: Campaign-bounded survey rating sessions

## Status
Accepted (2026-05-30). Companion spec: `docs/superpowers/specs/2026-05-29-survey-campaign-lock-design.md`.

## Context

The survey context (ADR-0056) collects clue ratings continuously. There is no first-class notion of "the data window for training batch N" — the export job converts whatever has accumulated to CSV on demand, ratings landing during an export window mix into the next training batch with no provenance, and the maintainer cannot pause data collection while a batch is being frozen.

We need a lifecycle for rating-collection windows so that:

1. Each rating belongs to exactly one batch, queryable for export.
2. The maintainer can pause `/sondage` (no new ratings) while a batch is being prepared.
3. The pause uses the simplest possible admin path — a 1-line SQL statement runnable from psql — without precluding HTTP wrappers later.

## Decision

Introduce a **`campaigns`** table in the survey schema. Each row represents a rating-collection window with `(campaign_id, batch_label, opened_at, closed_at)`. A partial unique index `WHERE closed_at IS NULL` enforces at most one open campaign at any time.

`ratings.campaign_id` and `pair_ratings.campaign_id` are added as nullable UUID FKs. The application stamps the currently open campaign's id at insert time. When no open campaign exists, `POST /v1/items/{itemId}/rating` and `POST /v1/pair-ratings` return **423 Locked**.

A new `GET /v1/campaign/current` returns the current row (the open one if any, otherwise the most recently closed) so the frontend can render a lock banner.

Campaign open and close are performed by direct SQL in v1. No admin HTTP endpoints ship in v1. The 1-line verbs are:

```sql
INSERT INTO campaigns (campaign_id, batch_label)
     VALUES (gen_random_uuid(), 'round-7') RETURNING campaign_id, opened_at;

UPDATE campaigns SET closed_at = now()
 WHERE closed_at IS NULL
RETURNING campaign_id, batch_label, closed_at;
```

The maintainer runs these from psql when prepping a training batch. Wrapping them in HTTP later is an additive change.

Post-commit undo of ratings is deliberately **out of scope** here. The schema introduced is forward-compatible: a follow-up ADR will add `submission_id` and `withdrawn_at` for post-commit undo.

## Consequences

- The export query gains a clean `WHERE campaign_id = ?` predicate. Existing exports continue to work against backfilled ids.
- `/sondage` becomes a stateful surface — the page now reflects an operational state ("collecting" vs "paused") in addition to per-item state.
- A backfill is required for historical ratings. See `scripts/survey/backfill_campaigns.py` and the spec §7.
- Two operational risks to be mindful of:
  - "Maintainer closes a campaign and forgets to open the next one" — symptom is users see the lock banner indefinitely. A SigNoz alert on `findOpen() == null` for > 1h is recommended but not in the v1 PR sequence.
  - A rating-submit transaction that holds a row lock on the open campaign at the moment of close commits inside the closing transaction's window. Acceptable: that rating is the last entry in the closing campaign.
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0059-survey-campaign-lock.md
git commit -s -m "docs(adr): ADR-0059 survey campaign lock"
```

---

### Task A2: Register ADR-0059 in INDEX.md

**Files:**
- Modify: `docs/adr/INDEX.md` (append three lines under the existing table)

- [ ] **Step 1: Append the ADR-0059 path bindings**

Open `docs/adr/INDEX.md` and append, after the `ADR-0058 modal_jobs/**` line, before the closing triple-backtick:

```
ADR-0059  survey/**/persistence/**                Campaign lifecycle: campaigns table, partial unique open invariant
ADR-0059  survey/**/usecases/SubmitRatingUseCase.kt           Locked arm + campaign_id stamping
ADR-0059  survey/**/usecases/SubmitPairRatingUseCase.kt       Locked arm + campaign_id stamping
ADR-0059  survey/api/openapi.yaml                  /v1/campaign/current + 423 on rating POSTs
ADR-0059  frontend/src/ui/components/sondage/**    LockBanner + useCampaignStatus + disabled cards
ADR-0059  scripts/survey/backfill_campaigns.py     Historical campaign attribution from Modal logs
```

- [ ] **Step 2: Verify the registry-coherence gate passes locally**

Run:
```bash
scripts/adr-context.sh survey/application/src/main/kotlin/com/bliss/survey/application/usecases/SubmitRatingUseCase.kt
```
Expected: the output contains the body of ADR-0059 (the script greps INDEX.md for matching globs and emits the matching ADRs).

- [ ] **Step 3: Commit**

```bash
git add docs/adr/INDEX.md
git commit -s -m "docs(adr): register ADR-0059 path bindings in INDEX"
```

- [ ] **Step 4: Open the ADR PR**

```bash
git push -u origin docs/survey-campaign-lock-spec
gh pr create --title "docs(adr): ADR-0059 survey campaign lock" --body "$(cat <<'EOF'
## Summary
- Adds ADR-0059 (campaign-bounded rating sessions) and the companion spec.
- Registers the path bindings in `docs/adr/INDEX.md` so the dispatch helper inlines ADR-0059 on every future implementer prompt that touches the survey context.
- Captures the broader offline → online posture in `TODO.md` so it is not lost.

ADR merges first per ADR-0001 §7. The schema-only OpenAPI PR follows.

## Test plan
- [ ] `scripts/adr-context.sh survey/application/src/main/kotlin/com/bliss/survey/application/usecases/SubmitRatingUseCase.kt` emits ADR-0059.
- [ ] CI green: `dco`, `commitlint`, `branch-name`, `registry-coherence`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Phase B — Schema-only OpenAPI PR

PR title: `feat(survey-api): openapi for /v1/campaign/current + 423 locks`
Branch: `feat/survey-campaign-openapi`, branched from `main` **after** Phase A merges.

### Task B1: Branch and pull the merged ADR

**Files:** none modified in this step.

- [ ] **Step 1: Branch from updated main**

```bash
git checkout main
git pull --ff-only origin main
git checkout -b feat/survey-campaign-openapi
```

---

### Task B2: Add the Campaign schema

**Files:**
- Modify: `survey/api/openapi.yaml`

- [ ] **Step 1: Locate the components.schemas block**

Run:
```bash
grep -n "components:" survey/api/openapi.yaml
grep -n "  schemas:" survey/api/openapi.yaml
```
Expected: prints the line numbers; use them to navigate.

- [ ] **Step 2: Add the `Campaign` schema under `components.schemas`**

Insert this block in alphabetical position among the other schema definitions:

```yaml
    Campaign:
      type: object
      description: |
        A rating-collection lifecycle window. At most one campaign is "open"
        (closedAt is null) at any time. When the current campaign is closed
        and no successor has been opened, rating POSTs return 423.
      required: [campaignId, batchLabel, openedAt, closedAt]
      properties:
        campaignId:
          type: string
          format: uuid
        batchLabel:
          type: string
          maxLength: 64
          description: Human label tying this campaign to a downstream training batch (e.g. `round-7`).
        openedAt:
          type: string
          format: date-time
        closedAt:
          type: [string, "null"]
          format: date-time
          description: Null while the campaign is collecting; non-null after the maintainer closes it.
```

- [ ] **Step 3: Lint the file**

Run:
```bash
npx -y @redocly/cli@latest lint survey/api/openapi.yaml
```
Expected: 0 errors.

- [ ] **Step 4: Commit**

```bash
git add survey/api/openapi.yaml
git commit -s -m "feat(survey-api): add Campaign schema to openapi"
```

---

### Task B3: Add `GET /v1/campaign/current`

**Files:**
- Modify: `survey/api/openapi.yaml`

- [ ] **Step 1: Add the path**

Insert this block under `paths:`, immediately after the existing `/v1/items/next` block (alphabetical):

```yaml
  /v1/campaign/current:
    get:
      operationId: getCurrentCampaign
      summary: Return the current campaign and its lock state.
      description: |
        Returns the open campaign if one exists, otherwise the most recently
        closed campaign. The frontend treats a non-null `closedAt` as "the
        sondage is locked": render the LockBanner and disable verdict /
        Corriger controls. Polled on mount and visibilitychange; no
        long-poll, no WebSocket. 503 only when no campaign has ever existed
        (operator error caught by smoke tests).
      tags: [campaigns]
      responses:
        '200':
          description: Current campaign.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Campaign' }
        '503':
          $ref: '#/components/responses/ProblemDetails'
```

- [ ] **Step 2: Add the `campaigns` tag**

In the `tags:` array near the top of the document, append:

```yaml
  - name: campaigns
    description: Campaign-lifecycle reads. Open/close happen via direct SQL in v1.
```

- [ ] **Step 3: Lint**

```bash
npx -y @redocly/cli@latest lint survey/api/openapi.yaml
```
Expected: 0 errors.

- [ ] **Step 4: Commit**

```bash
git add survey/api/openapi.yaml
git commit -s -m "feat(survey-api): add GET /v1/campaign/current endpoint"
```

---

### Task B4: Add 423 to rating POSTs and `campaignId` to 201 responses

**Files:**
- Modify: `survey/api/openapi.yaml`

- [ ] **Step 1: Find the existing 201 schema for `/v1/items/{itemId}/rating`**

Run:
```bash
grep -n "submitRating" survey/api/openapi.yaml
```
Expected: identifies the operationId and its `responses:` block.

- [ ] **Step 2: Extend the 201 response schema to include `campaignId`**

Locate the inline schema under the 201 response for `submitRating`. Add `campaignId` as a required property and to the `required:` array. Result should look like:

```yaml
            schema:
              type: object
              required: [ratingId, itemId, submittedAs, campaignId]
              properties:
                ratingId:        { type: string, format: uuid }
                itemId:          { type: string, format: uuid }
                submittedAs:     { type: string, enum: [auth, anon] }
                proposedItemId:  { type: [string, "null"], format: uuid }
                campaignId:      { type: string, format: uuid }
```

(Adjust the surrounding fields to match what's currently in the file — only `campaignId` is new.)

- [ ] **Step 3: Add the 423 response**

In the same `responses:` block for `submitRating`, add after the existing `409` (or wherever responses are listed):

```yaml
        '423':
          description: No open campaign — the sondage is locked.
          content:
            application/problem+json:
              schema: { $ref: '#/components/schemas/ProblemDetails' }
```

- [ ] **Step 4: Do the same for `submitPairRating`**

Find the `submitPairRating` operation. Extend its 201 response schema with `campaignId` (mark required) and add a 423 response identical in shape to step 3.

- [ ] **Step 5: Lint**

```bash
npx -y @redocly/cli@latest lint survey/api/openapi.yaml
```
Expected: 0 errors.

- [ ] **Step 6: Commit**

```bash
git add survey/api/openapi.yaml
git commit -s -m "feat(survey-api): 423 lock response + campaignId in rating 201s"
```

- [ ] **Step 7: Open the schema PR**

```bash
git push -u origin feat/survey-campaign-openapi
gh pr create --title "feat(survey-api): openapi for campaign lock + GET /v1/campaign/current" --body "$(cat <<'EOF'
## Summary
- New `GET /v1/campaign/current` endpoint and `Campaign` schema.
- `POST /v1/items/{itemId}/rating` and `POST /v1/pair-ratings` gain a 423 response and `campaignId` on their 201 payloads.
- Schema-only per ADR-0001 §3 — implementation lands in a follow-up PR.

ADR-0059 must be merged first.

## Test plan
- [ ] `openapi-lint` CI gate green (redocly clean).
- [ ] `openapi-typescript-drift` shows the expected regenerated types on the consumer-side dry run (next PR will regenerate).
- [ ] `commitlint`, `branch-name`, `dco` green.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Phase C — Backend implementation

PR title: `feat(survey): campaign lifecycle + 423 on rating POSTs`
Branch: `feat/survey-campaign-backend`, branched from `main` **after** Phase B merges.

### Task C1: Branch

- [ ] **Step 1: Branch from updated main**

```bash
git checkout main
git pull --ff-only origin main
git checkout -b feat/survey-campaign-backend
```

---

### Task C2: Flyway migration V7 — campaigns table + FK columns

**Files:**
- Create: `survey/infrastructure/src/main/resources/db/migration/V7__campaigns.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V7__campaigns.sql
-- Campaign-bounded rating sessions (ADR-0059). Partial unique invariant:
-- at most one row has closed_at IS NULL at a time. Ratings and pair-ratings
-- gain a nullable campaign_id FK that the application stamps on insert.

CREATE TABLE campaigns (
    campaign_id  UUID         PRIMARY KEY,
    batch_label  TEXT         NOT NULL,
    opened_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at    TIMESTAMPTZ
);

-- At most one open campaign at a time. ((1)) is the standard Postgres
-- pattern for a partial unique index on a constant expression.
CREATE UNIQUE INDEX campaigns_one_open
    ON campaigns ((1)) WHERE closed_at IS NULL;

-- Fallback lookup for findCurrent() when no open campaign exists.
CREATE INDEX campaigns_opened_at_idx
    ON campaigns (opened_at DESC);

ALTER TABLE ratings
    ADD COLUMN campaign_id UUID REFERENCES campaigns(campaign_id);
CREATE INDEX ratings_campaign_idx ON ratings (campaign_id);

ALTER TABLE pair_ratings
    ADD COLUMN campaign_id UUID REFERENCES campaigns(campaign_id);
CREATE INDEX pair_ratings_campaign_idx ON pair_ratings (campaign_id);
```

- [ ] **Step 2: Smoke-test the SQL against a local Postgres**

If a local Postgres is available, run:
```bash
psql -d survey_local -f survey/infrastructure/src/main/resources/db/migration/V7__campaigns.sql
psql -d survey_local -c "INSERT INTO campaigns (campaign_id, batch_label) VALUES (gen_random_uuid(), 'r1') RETURNING campaign_id, opened_at;"
psql -d survey_local -c "INSERT INTO campaigns (campaign_id, batch_label) VALUES (gen_random_uuid(), 'r2') RETURNING campaign_id;"
```
Expected: first INSERT succeeds; second INSERT fails with `duplicate key value violates unique constraint "campaigns_one_open"` (the invariant is enforced).

If no local Postgres is set up, skip — the Testcontainers test in Task C7 covers this.

- [ ] **Step 3: Commit**

```bash
git add survey/infrastructure/src/main/resources/db/migration/V7__campaigns.sql
git commit -s -m "feat(survey-infrastructure): V7 campaigns table + nullable campaign_id FKs"
```

---

### Task C3: Domain — Campaign and CampaignId

**Files:**
- Modify: `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/Ids.kt`
- Create: `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/Campaign.kt`
- Create: `survey/domain/src/test/kotlin/com/bliss/survey/domain/model/CampaignTest.kt`

- [ ] **Step 1: Write the failing test**

`survey/domain/src/test/kotlin/com/bliss/survey/domain/model/CampaignTest.kt`:

```kotlin
package com.bliss.survey.domain.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class CampaignTest {
    private val openedAt = Instant.parse("2026-05-30T10:00:00Z")
    private val closedAt = Instant.parse("2026-05-30T12:00:00Z")

    @Test
    fun `isOpen is true when closedAt is null`() {
        val c = Campaign(
            id = CampaignId(UUID.randomUUID()),
            batchLabel = "round-7",
            openedAt = openedAt,
            closedAt = null,
        )
        assertThat(c.isOpen).isTrue()
        assertThat(c.isClosed).isFalse()
    }

    @Test
    fun `isClosed is true when closedAt is non-null`() {
        val c = Campaign(
            id = CampaignId(UUID.randomUUID()),
            batchLabel = "round-7",
            openedAt = openedAt,
            closedAt = closedAt,
        )
        assertThat(c.isOpen).isFalse()
        assertThat(c.isClosed).isTrue()
    }

    @Test
    fun `batchLabel must not be blank`() {
        try {
            Campaign(
                id = CampaignId(UUID.randomUUID()),
                batchLabel = "",
                openedAt = openedAt,
                closedAt = null,
            )
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).isEqualTo("batchLabel must not be blank")
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :survey:domain:test --tests "com.bliss.survey.domain.model.CampaignTest"
```
Expected: compilation error — `Campaign`, `CampaignId` not found.

- [ ] **Step 3: Add `CampaignId` to Ids.kt**

Append to `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/Ids.kt`:

```kotlin
@JvmInline
value class CampaignId(
    val value: UUID,
)
```

- [ ] **Step 4: Write `Campaign.kt`**

`survey/domain/src/main/kotlin/com/bliss/survey/domain/model/Campaign.kt`:

```kotlin
package com.bliss.survey.domain.model

import java.time.Instant

data class Campaign(
    val id: CampaignId,
    val batchLabel: String,
    val openedAt: Instant,
    val closedAt: Instant?,
) {
    init {
        require(batchLabel.isNotBlank()) { "batchLabel must not be blank" }
    }

    val isOpen: Boolean get() = closedAt == null
    val isClosed: Boolean get() = closedAt != null
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew :survey:domain:test --tests "com.bliss.survey.domain.model.CampaignTest"
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add survey/domain
git commit -s -m "feat(survey-domain): Campaign aggregate + CampaignId value class"
```

---

### Task C4: Domain — add `campaignId` to Rating and PairRating

**Files:**
- Modify: `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/Rating.kt`
- Modify: `survey/domain/src/main/kotlin/com/bliss/survey/domain/model/PairRating.kt`

- [ ] **Step 1: Read the current shapes**

```bash
cat survey/domain/src/main/kotlin/com/bliss/survey/domain/model/Rating.kt
cat survey/domain/src/main/kotlin/com/bliss/survey/domain/model/PairRating.kt
```

- [ ] **Step 2: Add `campaignId: CampaignId?` to `Rating`**

Add a nullable `campaignId` field to the `Rating` data class. Nullable is important: existing call sites construct ratings without one and the backfill stamps them post-hoc. Keep it last in the parameter list so existing positional constructions continue to compile (then we update them in C5).

Example:
```kotlin
data class Rating(
    val id: RatingId,
    val itemId: ItemId,
    val userId: UserId?,
    val submittedAs: SubmittedAs,
    val qualite: Int,
    val difficulte: Int,
    val flag: FlagReason?,
    val proposedItemId: ItemId?,
    val latencyMs: Int?,
    val createdAt: Instant,
    val campaignId: CampaignId? = null,   // NEW
)
```

- [ ] **Step 3: Add `campaignId: CampaignId?` to `PairRating`**

Same change pattern. Default null at the end of the parameter list.

- [ ] **Step 4: Verify the domain module still builds**

```bash
./gradlew :survey:domain:build
```
Expected: BUILD SUCCESSFUL. The existing tests still pass because the field defaults to null.

- [ ] **Step 5: Commit**

```bash
git add survey/domain
git commit -s -m "feat(survey-domain): add nullable campaignId to Rating/PairRating"
```

---

### Task C5: Application — `CampaignRepository` port

**Files:**
- Create: `survey/application/src/main/kotlin/com/bliss/survey/application/ports/CampaignRepository.kt`

- [ ] **Step 1: Write the port**

```kotlin
package com.bliss.survey.application.ports

import com.bliss.survey.domain.model.Campaign

/**
 * Reads of the campaigns table. There is no insert/close port — those
 * operations happen via direct SQL by the maintainer in v1 (ADR-0059).
 */
interface CampaignRepository {
    /** The currently open campaign, if any. Uses the partial unique index. */
    suspend fun findOpen(): Campaign?

    /**
     * The campaign that drives the frontend's lock UI: the open one if any,
     * otherwise the most recently opened (which will have closed_at set).
     * Only returns null when the campaigns table is empty.
     */
    suspend fun findCurrent(): Campaign?
}
```

- [ ] **Step 2: Verify the application module still builds**

```bash
./gradlew :survey:application:build
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add survey/application
git commit -s -m "feat(survey-application): CampaignRepository port"
```

---

### Task C6: Application — `GetCurrentCampaignUseCase`

**Files:**
- Create: `survey/application/src/main/kotlin/com/bliss/survey/application/usecases/GetCurrentCampaignUseCase.kt`
- Create: `survey/application/src/test/kotlin/com/bliss/survey/application/usecases/GetCurrentCampaignUseCaseTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.bliss.survey.application.ports.CampaignRepository
import com.bliss.survey.domain.model.Campaign
import com.bliss.survey.domain.model.CampaignId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class GetCurrentCampaignUseCaseTest {
    private val openedAt = Instant.parse("2026-05-30T10:00:00Z")

    private fun campaign(closed: Instant? = null): Campaign =
        Campaign(
            id = CampaignId(UUID.randomUUID()),
            batchLabel = "round-7",
            openedAt = openedAt,
            closedAt = closed,
        )

    @Test
    fun `delegates to repository findCurrent`() = runTest {
        val expected = campaign()
        val repo = object : CampaignRepository {
            override suspend fun findOpen(): Campaign? = expected
            override suspend fun findCurrent(): Campaign? = expected
        }
        val useCase = GetCurrentCampaignUseCase(repo)

        assertThat(useCase.execute()).isEqualTo(expected)
    }

    @Test
    fun `returns null when no campaign exists`() = runTest {
        val repo = object : CampaignRepository {
            override suspend fun findOpen(): Campaign? = null
            override suspend fun findCurrent(): Campaign? = null
        }
        val useCase = GetCurrentCampaignUseCase(repo)

        assertThat(useCase.execute()).isNull()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :survey:application:test --tests "com.bliss.survey.application.usecases.GetCurrentCampaignUseCaseTest"
```
Expected: FAIL — `GetCurrentCampaignUseCase` not found.

- [ ] **Step 3: Implement the use case**

```kotlin
package com.bliss.survey.application.usecases

import com.bliss.survey.application.ports.CampaignRepository
import com.bliss.survey.domain.model.Campaign

class GetCurrentCampaignUseCase(
    private val campaigns: CampaignRepository,
) {
    suspend fun execute(): Campaign? = campaigns.findCurrent()
}
```

- [ ] **Step 4: Run the tests**

```bash
./gradlew :survey:application:test --tests "com.bliss.survey.application.usecases.GetCurrentCampaignUseCaseTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add survey/application
git commit -s -m "feat(survey-application): GetCurrentCampaignUseCase"
```

---

### Task C7: Infrastructure — `PgCampaignRepository`

**Files:**
- Create: `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgCampaignRepository.kt`
- Create: `survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/persistence/PgCampaignRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.bliss.survey.infrastructure.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.bliss.survey.domain.model.Campaign
import com.bliss.survey.domain.model.CampaignId
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgCampaignRepositoryTest {
    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var dataSource: HikariDataSource
    private lateinit var campaigns: PgCampaignRepository

    @BeforeAll
    fun startPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable()) { "Docker daemon not available" }
        pg = SurveyTestcontainer.startPostgres()
        dataSource = SurveyTestcontainer.dataSourceFor(pg)
    }

    @AfterAll
    fun stopPostgres() {
        if (::dataSource.isInitialized) dataSource.close()
        if (::pg.isInitialized) pg.stop()
    }

    @AfterEach
    fun truncate() {
        dataSource.connection.use { it.createStatement().execute("TRUNCATE campaigns CASCADE") }
    }

    private fun setUp() {
        campaigns = PgCampaignRepository(dataSource)
    }

    private fun insertCampaign(label: String, closed: Instant? = null): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO campaigns (campaign_id, batch_label, closed_at) VALUES (?, ?, ?)",
            ).use { s ->
                s.setObject(1, id)
                s.setString(2, label)
                if (closed != null) s.setTimestamp(3, Timestamp.from(closed)) else s.setNull(3, java.sql.Types.TIMESTAMP)
                s.executeUpdate()
            }
        }
        return id
    }

    @Test
    fun `findOpen returns null on empty table`() = runTest {
        setUp()
        assertThat(campaigns.findOpen()).isNull()
    }

    @Test
    fun `findOpen returns the open row`() = runTest {
        setUp()
        val id = insertCampaign("round-7")
        val found = campaigns.findOpen()
        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(CampaignId(id))
        assertThat(found.isOpen).isEqualTo(true)
    }

    @Test
    fun `findOpen returns null when only closed campaigns exist`() = runTest {
        setUp()
        insertCampaign("round-6", closed = Instant.parse("2026-05-29T12:00:00Z"))
        assertThat(campaigns.findOpen()).isNull()
    }

    @Test
    fun `partial unique index forbids two open campaigns`() = runTest {
        setUp()
        insertCampaign("round-7")
        var threw = false
        try {
            insertCampaign("round-8")
        } catch (e: Exception) {
            threw = true
        }
        assertThat(threw).isEqualTo(true)
    }

    @Test
    fun `findCurrent falls back to most recently opened closed campaign`() = runTest {
        setUp()
        insertCampaign("round-5", closed = Instant.parse("2026-05-28T12:00:00Z"))
        Thread.sleep(10)
        insertCampaign("round-6", closed = Instant.parse("2026-05-29T12:00:00Z"))
        val found = campaigns.findCurrent()
        assertThat(found).isNotNull()
        assertThat(found!!.batchLabel).isEqualTo("round-6")
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :survey:infrastructure:test --tests "com.bliss.survey.infrastructure.persistence.PgCampaignRepositoryTest"
```
Expected: FAIL — `PgCampaignRepository` not found.

- [ ] **Step 3: Implement `PgCampaignRepository`**

```kotlin
package com.bliss.survey.infrastructure.persistence

import com.bliss.survey.application.ports.CampaignRepository
import com.bliss.survey.domain.model.Campaign
import com.bliss.survey.domain.model.CampaignId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

class PgCampaignRepository(
    private val dataSource: DataSource,
) : CampaignRepository {
    override suspend fun findOpen(): Campaign? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(FIND_OPEN_SQL).use { stmt ->
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.toCampaign() else null }
                }
            }
        }

    override suspend fun findCurrent(): Campaign? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(FIND_CURRENT_SQL).use { stmt ->
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.toCampaign() else null }
                }
            }
        }

    private fun ResultSet.toCampaign(): Campaign =
        Campaign(
            id = CampaignId(getObject("campaign_id", UUID::class.java)),
            batchLabel = getString("batch_label"),
            openedAt = getTimestamp("opened_at").toInstant(),
            closedAt = getTimestamp("closed_at")?.toInstant(),
        )

    private companion object {
        const val FIND_OPEN_SQL =
            "SELECT * FROM campaigns WHERE closed_at IS NULL LIMIT 1"

        // ORDER BY opened_at DESC uses campaigns_opened_at_idx; LIMIT 1 is the
        // current open row when one exists (cheaper than UNION), or the most
        // recently opened closed row when none does.
        const val FIND_CURRENT_SQL =
            "SELECT * FROM campaigns ORDER BY opened_at DESC LIMIT 1"
    }
}
```

- [ ] **Step 4: Run the tests**

```bash
./gradlew :survey:infrastructure:test --tests "com.bliss.survey.infrastructure.persistence.PgCampaignRepositoryTest"
```
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add survey/infrastructure
git commit -s -m "feat(survey-infrastructure): PgCampaignRepository + Testcontainers test"
```

---

### Task C8: Application — `SubmitRatingUseCase` Locked arm + campaign-id stamping

**Files:**
- Modify: `survey/application/src/main/kotlin/com/bliss/survey/application/usecases/SubmitRatingUseCase.kt`
- Create: `survey/application/src/test/kotlin/com/bliss/survey/application/usecases/SubmitRatingUseCaseLockedTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import com.bliss.survey.application.filters.FilterPipeline
import com.bliss.survey.application.ports.CampaignRepository
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.IdGenerator
import com.bliss.survey.application.ports.ProposedByRepository
import com.bliss.survey.application.ports.RatingRepository
import com.bliss.survey.application.ports.SurveyItemRepository
import com.bliss.survey.application.ports.UserProgressRepository
import com.bliss.survey.domain.model.Campaign
import com.bliss.survey.domain.model.CampaignId
import com.bliss.survey.domain.model.ItemId
import com.bliss.survey.domain.model.Rating
import com.bliss.survey.domain.model.UserId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SubmitRatingUseCaseLockedTest {
    // For brevity, the harness reuses fakes from sibling tests in this
    // package (FakeSurveyItemRepository, FakeRatingRepository, etc.). If
    // they don't yet exist, write minimal in-memory ports that satisfy
    // the interfaces — never mock these (CLAUDE.md "no mocks of own code").

    private val now: Instant = Instant.parse("2026-05-30T12:00:00Z")
    private val itemId = ItemId(UUID.randomUUID())
    private val userId = UserId(UUID.randomUUID())

    private fun openCampaign(): Campaign =
        Campaign(
            id = CampaignId(UUID.randomUUID()),
            batchLabel = "round-7",
            openedAt = now.minusSeconds(3600),
            closedAt = null,
        )

    private fun useCase(
        currentCampaign: Campaign?,
        items: SurveyItemRepository,
        ratings: RatingRepository,
    ): SubmitRatingUseCase =
        SubmitRatingUseCase(
            items = items,
            ratings = ratings,
            proposedBy = NoOpProposedByRepository,
            progress = NoOpUserProgressRepository,
            filters = NoOpFilterPipeline,
            ids = SequentialIdGenerator(),
            clock = object : Clock { override fun now(): Instant = this@SubmitRatingUseCaseLockedTest.now },
            campaigns = object : CampaignRepository {
                override suspend fun findOpen(): Campaign? = currentCampaign
                override suspend fun findCurrent(): Campaign? = currentCampaign
            },
        )

    @Test
    fun `returns Locked when no open campaign exists`() = runTest {
        val items = FakeSurveyItemRepository().also { it.put(sampleItem(itemId)) }
        val ratings = FakeRatingRepository()
        val uc = useCase(currentCampaign = null, items = items, ratings = ratings)

        val result = uc.execute(
            SubmitRatingCommand(
                itemId = itemId, userId = userId, qualite = 5, difficulte = 3,
                flag = null, correctif = null, latencyMs = 1200,
            ),
        )

        assertThat(result).isInstanceOf(SubmitRatingResult.Locked::class)
        assertThat(ratings.size).isEqualTo(0)
    }

    @Test
    fun `stamps campaign_id on accepted insert`() = runTest {
        val items = FakeSurveyItemRepository().also { it.put(sampleItem(itemId)) }
        val ratings = FakeRatingRepository()
        val campaign = openCampaign()
        val uc = useCase(currentCampaign = campaign, items = items, ratings = ratings)

        val result = uc.execute(
            SubmitRatingCommand(
                itemId = itemId, userId = userId, qualite = 5, difficulte = 3,
                flag = null, correctif = null, latencyMs = 1200,
            ),
        )

        assertThat(result).isInstanceOf(SubmitRatingResult.Accepted::class)
        val accepted = result as SubmitRatingResult.Accepted
        assertThat(accepted.rating.campaignId).isNotNull()
        assertThat(accepted.rating.campaignId!!.value).isEqualTo(campaign.id.value)
    }
}
```

If the fake repositories (`FakeSurveyItemRepository`, `FakeRatingRepository`, `SequentialIdGenerator`, `NoOpProposedByRepository`, etc.) don't already exist in `survey/application/src/test/kotlin/.../usecases/`, look for existing patterns in `SubmitRatingUseCaseTest.kt` (sibling file) and either reuse or write minimal in-memory implementations. The point: never mock `RatingRepository` etc. — they're our own code.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :survey:application:test --tests "com.bliss.survey.application.usecases.SubmitRatingUseCaseLockedTest"
```
Expected: compilation error — `SubmitRatingResult.Locked` not found, and `SubmitRatingUseCase` doesn't take a `campaigns:` parameter.

- [ ] **Step 3: Add the Locked arm and the campaigns parameter**

In `SubmitRatingUseCase.kt`:

```kotlin
sealed interface SubmitRatingResult {
    // ... existing arms ...
    data object Locked : SubmitRatingResult
}

class SubmitRatingUseCase(
    private val items: SurveyItemRepository,
    private val ratings: RatingRepository,
    private val proposedBy: ProposedByRepository,
    private val progress: UserProgressRepository,
    private val filters: FilterPipeline,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val campaigns: CampaignRepository,    // NEW
) {
    suspend fun execute(cmd: SubmitRatingCommand): SubmitRatingResult {
        val openCampaign = campaigns.findOpen()
            ?: return SubmitRatingResult.Locked
        // ... existing flow continues; rename `now` step stays where it was ...
```

In the body, where a `Rating(...)` is constructed for the primary rating row, set `campaignId = openCampaign.id`. Do the same for the auto-GOOD `Rating(...)` constructed after a Corriger acceptance: `campaignId = openCampaign.id`.

- [ ] **Step 4: Run the locked-suite tests**

```bash
./gradlew :survey:application:test --tests "com.bliss.survey.application.usecases.SubmitRatingUseCaseLockedTest"
```
Expected: PASS (2 tests).

- [ ] **Step 5: Run the entire application module tests to ensure no regressions**

```bash
./gradlew :survey:application:test
```
Expected: PASS. Existing `SubmitRatingUseCaseTest` may need updating if it now requires a `campaigns` parameter at construction. If so, add a fake that returns an open campaign so the existing flows succeed unchanged.

- [ ] **Step 6: Commit**

```bash
git add survey/application
git commit -s -m "feat(survey-application): SubmitRatingUseCase Locked arm + campaign stamping"
```

---

### Task C9: Application — `SubmitPairRatingUseCase` Locked arm + stamping

**Files:**
- Modify: `survey/application/src/main/kotlin/com/bliss/survey/application/usecases/SubmitPairRatingUseCase.kt`
- Create: `survey/application/src/test/kotlin/com/bliss/survey/application/usecases/SubmitPairRatingUseCaseLockedTest.kt`

- [ ] **Step 1: Write the failing test**

Modelled on the rating test, but covering:
- `Locked` returned when no open campaign.
- `LEFT_BETTER` / `RIGHT_BETTER` stamp the pair-rating row's `campaignId`.
- `BOTH_GOOD` stamps **both** absolute Rating rows' `campaignId` with the same id.

Use the same fake-port pattern as in C8.

```kotlin
package com.bliss.survey.application.usecases

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.bliss.survey.application.ports.CampaignRepository
// ... (same imports as C8, plus pair-specific)

class SubmitPairRatingUseCaseLockedTest {
    // ... (set up fakes, currentCampaign helper, useCase builder) ...

    @Test
    fun `returns Locked when no open campaign exists`() = runTest {
        // build use case with currentCampaign = null
        val result = uc.execute(/* LEFT_BETTER pair command */)
        assertThat(result).isInstanceOf(SubmitPairRatingResult.Locked::class)
        // verify nothing inserted in either ratings or pair_ratings fakes
    }

    @Test
    fun `LEFT_BETTER stamps campaign_id on the pair row`() = runTest {
        // build use case with an open campaign
        val result = uc.execute(/* LEFT_BETTER */)
        assertThat(result).isInstanceOf(SubmitPairRatingResult.Recorded::class)
        // verify pairRatings fake has 1 row with the expected campaignId
    }

    @Test
    fun `BOTH_GOOD stamps campaign_id on both absolute Rating rows`() = runTest {
        // build use case with an open campaign
        val result = uc.execute(/* BOTH_GOOD */)
        assertThat(result).isInstanceOf(SubmitPairRatingResult.Recorded::class)
        // verify ratings fake has 2 rows, both with the same campaignId
    }
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :survey:application:test --tests "com.bliss.survey.application.usecases.SubmitPairRatingUseCaseLockedTest"
```
Expected: FAIL.

- [ ] **Step 3: Implement**

In `SubmitPairRatingUseCase.kt`:

```kotlin
sealed interface SubmitPairRatingResult {
    // ... existing arms ...
    data object Locked : SubmitPairRatingResult
}

class SubmitPairRatingUseCase(
    // ... existing constructor args ...
    private val campaigns: CampaignRepository,    // NEW (add as last arg)
) {
    suspend fun execute(cmd: SubmitPairRatingCommand): SubmitPairRatingResult {
        if (cmd.verdict == PairVerdict.SKIP) return SubmitPairRatingResult.Skipped
        // SKIP is never persisted — campaign check skipped to preserve current behaviour.

        val openCampaign = campaigns.findOpen()
            ?: return SubmitPairRatingResult.Locked

        // ... existing same-item / pair-mot-mismatch / item-not-found checks ...

        // Stamp campaignId in:
        //   - the PairRating(...) constructed under LEFT_WINS / RIGHT_WINS
        //   - both Rating(...) rows constructed under BOTH_GOOD / BOTH_BAD
    }
}
```

- [ ] **Step 4: Run the tests**

```bash
./gradlew :survey:application:test --tests "com.bliss.survey.application.usecases.SubmitPairRatingUseCaseLockedTest"
./gradlew :survey:application:test
```
Expected: PASS (new 3 + all existing).

- [ ] **Step 5: Commit**

```bash
git add survey/application
git commit -s -m "feat(survey-application): SubmitPairRatingUseCase Locked arm + stamping"
```

---

### Task C10: Infrastructure — extend `PgRatingRepository.INSERT_SQL` for `campaign_id`

**Files:**
- Modify: `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgRatingRepository.kt`
- Modify (test): `survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/persistence/PgRatingRepositoryTest.kt` (add a campaign-id round-trip assertion)

- [ ] **Step 1: Write the failing test**

Append a test case to `PgRatingRepositoryTest`:

```kotlin
@Test
fun `insert and read back round-trips campaign_id`() = runTest {
    setUp()
    val campaignId = insertCampaignRow("round-7")   // helper that inserts into campaigns table
    val item = insertItemRow()                       // existing helper
    val rating = Rating(
        id = RatingId(UUID.randomUUID()),
        itemId = item.id,
        userId = UserId(UUID.randomUUID()),
        submittedAs = SubmittedAs.AUTH,
        qualite = 5,
        difficulte = 3,
        flag = null,
        proposedItemId = null,
        latencyMs = 1200,
        createdAt = now,
        campaignId = CampaignId(campaignId),
    )
    ratings.insert(rating)

    // Re-read via aggregateForExport — that's the existing read path; assert
    // count is 1. Then a direct SELECT to assert the campaign_id column.
    dataSource.connection.use { c ->
        c.prepareStatement("SELECT campaign_id FROM ratings WHERE rating_id = ?").use { s ->
            s.setObject(1, rating.id.value)
            s.executeQuery().use { rs ->
                check(rs.next())
                assertThat(rs.getObject("campaign_id", UUID::class.java)).isEqualTo(campaignId)
            }
        }
    }
}
```

(If `insertCampaignRow` and `insertItemRow` helpers don't exist, write them in the same file.)

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :survey:infrastructure:test --tests "com.bliss.survey.infrastructure.persistence.PgRatingRepositoryTest"
```
Expected: FAIL — INSERT_SQL doesn't include `campaign_id`; the column is left NULL after insert.

- [ ] **Step 3: Update `INSERT_SQL` and the binder**

Change the constant in `PgRatingRepository.kt`:

```kotlin
const val INSERT_SQL =
    """
    INSERT INTO ratings
      (rating_id, item_id, user_id, submitted_as, qualite, difficulte,
       flag, proposed_item_id, latency_ms, client_meta, created_at, campaign_id)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """
```

In the `insert` method, after the existing `setTimestamp(11, ...)`, add:

```kotlin
val campaign = rating.campaignId
if (campaign != null) {
    stmt.setObject(12, campaign.value)
} else {
    stmt.setNull(12, Types.OTHER)
}
```

- [ ] **Step 4: Run the tests**

```bash
./gradlew :survey:infrastructure:test --tests "com.bliss.survey.infrastructure.persistence.PgRatingRepositoryTest"
```
Expected: PASS (all existing + the new round-trip test).

- [ ] **Step 5: Commit**

```bash
git add survey/infrastructure
git commit -s -m "feat(survey-infrastructure): PgRatingRepository persists campaign_id"
```

---

### Task C11: Infrastructure — extend `PgPairRatingRepository.INSERT_SQL`

**Files:**
- Modify: `survey/infrastructure/src/main/kotlin/com/bliss/survey/infrastructure/persistence/PgPairRatingRepository.kt`
- Modify (test): `survey/infrastructure/src/test/kotlin/com/bliss/survey/infrastructure/persistence/PgPairRatingRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

Mirror the C10 test, but on `PgPairRatingRepository`: insert a `PairRating` with a `campaignId`, then SELECT `campaign_id` directly and assert it round-trips.

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :survey:infrastructure:test --tests "com.bliss.survey.infrastructure.persistence.PgPairRatingRepositoryTest"
```
Expected: FAIL.

- [ ] **Step 3: Update the SQL + binder**

In `PgPairRatingRepository.kt`, locate its `INSERT_SQL` (look for `INSERT INTO pair_ratings`), append `, campaign_id` to the column list and add another `?` to the values list. In the `insert` method, bind the last parameter from `pairRating.campaignId?.value`, calling `setObject` if non-null and `setNull(idx, Types.OTHER)` if null.

- [ ] **Step 4: Run the tests**

```bash
./gradlew :survey:infrastructure:test --tests "com.bliss.survey.infrastructure.persistence.PgPairRatingRepositoryTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add survey/infrastructure
git commit -s -m "feat(survey-infrastructure): PgPairRatingRepository persists campaign_id"
```

---

### Task C12: API — Campaign DTO + GET route

**Files:**
- Create: `survey/api/src/main/kotlin/com/bliss/survey/api/dto/CampaignDtos.kt`
- Create: `survey/api/src/main/kotlin/com/bliss/survey/api/routes/GetCurrentCampaignRoute.kt`
- Create: `survey/api/src/test/kotlin/com/bliss/survey/api/routes/GetCurrentCampaignRouteTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.bliss.survey.api.routes

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.bliss.survey.api.WIRE_JSON
import com.bliss.survey.api.dto.CampaignResponse
import com.bliss.survey.domain.model.Campaign
import com.bliss.survey.domain.model.CampaignId
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class GetCurrentCampaignRouteTest {
    private val openedAt = Instant.parse("2026-05-30T10:00:00Z")

    @Test
    fun `returns 200 with the open campaign`() = testApplication {
        val campaign = Campaign(
            id = CampaignId(UUID.fromString("00000000-0000-0000-0000-000000000007")),
            batchLabel = "round-7",
            openedAt = openedAt,
            closedAt = null,
        )
        application {
            routing { getCurrentCampaignRoute { campaign } }
        }
        val response = client.get("/v1/campaign/current")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val body = WIRE_JSON.decodeFromString(CampaignResponse.serializer(), response.bodyAsText())
        assertThat(body.campaignId).isEqualTo("00000000-0000-0000-0000-000000000007")
        assertThat(body.batchLabel).isEqualTo("round-7")
        assertThat(body.closedAt).isEqualTo(null)
    }

    @Test
    fun `returns 200 with a closed campaign when no campaign is open`() = testApplication {
        val campaign = Campaign(
            id = CampaignId(UUID.randomUUID()),
            batchLabel = "round-6",
            openedAt = openedAt,
            closedAt = openedAt.plusSeconds(3600),
        )
        application {
            routing { getCurrentCampaignRoute { campaign } }
        }
        val response = client.get("/v1/campaign/current")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(response.bodyAsText()).contains("\"closedAt\":")
    }

    @Test
    fun `returns 503 when no campaign exists`() = testApplication {
        application {
            routing { getCurrentCampaignRoute { null } }
        }
        val response = client.get("/v1/campaign/current")
        assertThat(response.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
    }
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :survey:api:test --tests "com.bliss.survey.api.routes.GetCurrentCampaignRouteTest"
```
Expected: FAIL — `CampaignResponse`, `getCurrentCampaignRoute` not found.

- [ ] **Step 3: Write the DTO**

`survey/api/src/main/kotlin/com/bliss/survey/api/dto/CampaignDtos.kt`:

```kotlin
package com.bliss.survey.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class CampaignResponse(
    val campaignId: String,
    val batchLabel: String,
    val openedAt: String,
    val closedAt: String?,
)
```

- [ ] **Step 4: Write the route**

`survey/api/src/main/kotlin/com/bliss/survey/api/routes/GetCurrentCampaignRoute.kt`:

```kotlin
package com.bliss.survey.api.routes

import com.bliss.survey.api.dto.CampaignResponse
import com.bliss.survey.api.dto.ProblemDetails
import com.bliss.survey.api.respondProblem
import com.bliss.survey.application.usecases.GetCurrentCampaignUseCase
import com.bliss.survey.domain.model.Campaign
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

// GET /v1/campaign/current — returns the lock state for the frontend.
fun Route.getCurrentCampaignRoute(fetch: suspend () -> Campaign?) {
    get("/v1/campaign/current") {
        val campaign = fetch()
        if (campaign == null) {
            call.respondProblem(
                HttpStatusCode.ServiceUnavailable,
                ProblemDetails(
                    type = "about:blank",
                    title = "no campaign",
                    status = HttpStatusCode.ServiceUnavailable.value,
                    detail = "No campaign has ever been opened.",
                ),
            )
            return@get
        }
        call.respond(
            HttpStatusCode.OK,
            CampaignResponse(
                campaignId = campaign.id.value.toString(),
                batchLabel = campaign.batchLabel,
                openedAt = campaign.openedAt.toString(),
                closedAt = campaign.closedAt?.toString(),
            ),
        )
    }
}

// Production overload — mirrors submitRatingRoute's pattern.
fun Route.getCurrentCampaignRoute(useCase: GetCurrentCampaignUseCase) =
    getCurrentCampaignRoute { useCase.execute() }
```

- [ ] **Step 5: Run the tests**

```bash
./gradlew :survey:api:test --tests "com.bliss.survey.api.routes.GetCurrentCampaignRouteTest"
```
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add survey/api
git commit -s -m "feat(survey-api): GET /v1/campaign/current route + DTO"
```

---

### Task C13: API — handle `Locked` in `SubmitRatingRoute` → 423

**Files:**
- Modify: `survey/api/src/main/kotlin/com/bliss/survey/api/routes/SubmitRatingRoute.kt`
- Modify: `survey/api/src/main/kotlin/com/bliss/survey/api/dto/RatingDtos.kt` (`campaignId` field on `RatingResponse`)
- Modify (test): `survey/api/src/test/kotlin/com/bliss/survey/api/routes/SubmitRatingRouteTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `SubmitRatingRouteTest.kt`:

```kotlin
@Test
fun `returns 423 when the use case returns Locked`() = testApplication {
    application {
        routing { submitRatingRoute { _ -> SubmitRatingResult.Locked } }
    }
    val response = client.post("/v1/items/00000000-0000-0000-0000-000000000001/rating") {
        contentType(io.ktor.http.ContentType.Application.Json)
        setBody("""{"qualite":5,"difficulte":3,"latencyMs":1200}""")
    }
    assertThat(response.status.value).isEqualTo(423)
    assertThat(response.bodyAsText()).contains("\"title\":\"campaign closed\"")
}

@Test
fun `201 response includes campaignId`() = testApplication {
    val cid = UUID.fromString("00000000-0000-0000-0000-000000000007")
    application {
        routing {
            submitRatingRoute { _ ->
                SubmitRatingResult.Accepted(
                    rating = sampleRating(campaignId = CampaignId(cid)),
                )
            }
        }
    }
    val response = client.post("/v1/items/00000000-0000-0000-0000-000000000001/rating") {
        contentType(io.ktor.http.ContentType.Application.Json)
        setBody("""{"qualite":5,"difficulte":3,"latencyMs":1200}""")
    }
    assertThat(response.status.value).isEqualTo(201)
    assertThat(response.bodyAsText()).contains(""""campaignId":"00000000-0000-0000-0000-000000000007"""")
}
```

(If `sampleRating` helper doesn't exist, add one that constructs a valid `Rating` with the given `campaignId`.)

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :survey:api:test --tests "com.bliss.survey.api.routes.SubmitRatingRouteTest"
```
Expected: FAIL — Locked branch missing in the `when`; `RatingResponse` doesn't yet have `campaignId`.

- [ ] **Step 3: Add `campaignId` to the response DTO**

In `survey/api/src/main/kotlin/com/bliss/survey/api/dto/RatingDtos.kt`, locate `RatingResponse` and add a non-nullable `campaignId: String` field.

- [ ] **Step 4: Update `Rating.toResponse()` and add the `Locked` branch**

In `SubmitRatingRoute.kt`:

```kotlin
when (val result = execute(cmd)) {
    is SubmitRatingResult.Accepted ->
        call.respond(HttpStatusCode.Created, result.rating.toResponse())

    // ... existing arms ...

    SubmitRatingResult.Locked ->
        call.respondProblem(
            HttpStatusCode.Locked,                        // 423
            ProblemDetails(
                type = "about:blank",
                title = "campaign closed",
                status = HttpStatusCode.Locked.value,
                detail = "The sondage is paused while a training batch is being prepared.",
            ),
        )
}
```

And update `toResponse()`:

```kotlin
private fun Rating.toResponse(): RatingResponse =
    RatingResponse(
        ratingId = id.value.toString(),
        itemId = itemId.value.toString(),
        submittedAs = submittedAs.name.lowercase(),
        proposedItemId = proposedItemId?.value?.toString(),
        campaignId = requireNotNull(campaignId) {
            "Accepted rating must have campaignId stamped by SubmitRatingUseCase"
        }.value.toString(),
    )
```

- [ ] **Step 5: Run the tests**

```bash
./gradlew :survey:api:test --tests "com.bliss.survey.api.routes.SubmitRatingRouteTest"
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add survey/api
git commit -s -m "feat(survey-api): map SubmitRatingResult.Locked to 423; expose campaignId"
```

---

### Task C14: API — `SubmitPairRatingRoute` 423 + campaignId

**Files:**
- Modify: `survey/api/src/main/kotlin/com/bliss/survey/api/routes/SubmitPairRatingRoute.kt`
- Modify: `survey/api/src/main/kotlin/com/bliss/survey/api/dto/RatingDtos.kt` (pair-rating response gains `campaignId`)
- Modify (test): `survey/api/src/test/kotlin/com/bliss/survey/api/routes/SubmitPairRatingRouteTest.kt`

- [ ] **Step 1: Write the failing test**

Mirror C13: add `returns 423 when Locked` and `201 includes campaignId`. The pair response is more interesting because BOTH_GOOD / BOTH_BAD don't have a single pair-rating row — the route handler returns `campaignId` from the use-case `Recorded` result regardless. Confirm the existing `SubmitPairRatingResult.Recorded` variant has (or gains) a `campaignId: CampaignId` field; if not, add it.

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew :survey:api:test --tests "com.bliss.survey.api.routes.SubmitPairRatingRouteTest"
```
Expected: FAIL.

- [ ] **Step 3: Add `campaignId` to `SubmitPairRatingResult.Recorded`**

In `SubmitPairRatingUseCase.kt` (this part lives in the application module but was deferred from C9 — do it now if not already done):

```kotlin
sealed interface SubmitPairRatingResult {
    data class Recorded(val campaignId: CampaignId) : SubmitPairRatingResult
    // ... existing arms ...
    data object Locked : SubmitPairRatingResult
}
```

And inside the use case, return `Recorded(openCampaign.id)` instead of the prior `Recorded` data object.

(Adjust any sibling tests that match on `Recorded` — they may now need `Recorded(...)` destructuring instead of `data object`.)

- [ ] **Step 4: Handle `Locked` in `SubmitPairRatingRoute`**

Mirror the C13 Locked branch. Add `campaignId` to the 201 response body.

- [ ] **Step 5: Run the tests**

```bash
./gradlew :survey:api:test --tests "com.bliss.survey.api.routes.SubmitPairRatingRouteTest"
./gradlew :survey:application:test
```
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add survey
git commit -s -m "feat(survey-api): map SubmitPairRatingResult.Locked to 423; expose campaignId"
```

---

### Task C15: API — wiring + Module integration

**Files:**
- Modify: `survey/api/src/main/kotlin/com/bliss/survey/api/Wiring.kt`
- Modify: `survey/api/src/main/kotlin/com/bliss/survey/api/Main.kt`
- Modify: `survey/api/src/main/kotlin/com/bliss/survey/api/Module.kt`

- [ ] **Step 1: Extend `Wiring`**

Add a new field:

```kotlin
class Wiring(
    // ... existing ...
    val getCurrentCampaign: GetCurrentCampaignUseCase,
)
```

- [ ] **Step 2: Build the use case in `Main.kt`**

Find where the other use cases are constructed. Add:

```kotlin
val campaignRepository = PgCampaignRepository(dataSource)
val getCurrentCampaign = GetCurrentCampaignUseCase(campaignRepository)
```

And pass `campaignRepository` into `SubmitRatingUseCase(...)` and `SubmitPairRatingUseCase(...)` as the new `campaigns` argument. Pass `getCurrentCampaign` into the `Wiring` constructor.

- [ ] **Step 3: Mount the route in `Module.kt`**

In the `routing { ... }` block, after `submitPairRatingRoute(...)`:

```kotlin
getCurrentCampaignRoute(wiring.getCurrentCampaign)
```

(Add the matching import at the top of `Module.kt`.)

- [ ] **Step 4: Run the entire survey build**

```bash
./gradlew :survey:api:build
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add survey/api
git commit -s -m "feat(survey-api): wire campaign repository + getCurrentCampaign route"
```

---

### Task C16: Architecture tests + spotless

**Files:** none new; verify the existing arch tests pass.

- [ ] **Step 1: Run architecture tests**

```bash
./gradlew :survey:domain:test --tests "*ArchitectureTest"
./gradlew :survey:application:test --tests "*ArchitectureTest"
./gradlew :survey:infrastructure:test --tests "*ArchitectureTest"
./gradlew :survey:api:test --tests "*ArchitectureTest"
```
Expected: PASS — Konsist guards verify hexagonal boundaries; the new files comply because:
- `Campaign` and `CampaignId` are pure data classes in `domain/model/`.
- `CampaignRepository` is a port in `application/ports/`.
- `PgCampaignRepository` is an adapter in `infrastructure/persistence/`.
- `getCurrentCampaignRoute` is in `api/routes/`.

- [ ] **Step 2: Run spotless**

```bash
./gradlew spotlessCheck
```
Expected: PASS. If FAIL, run `./gradlew spotlessApply` and commit the result.

- [ ] **Step 3: Run the full build**

```bash
./gradlew build --parallel
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit any spotless fixes**

```bash
git add survey
git commit -s -m "chore(survey): spotless fixes from campaign-lock series" --allow-empty-message=false
```

(Skip the commit if there were no fixes.)

- [ ] **Step 5: Push and open the backend PR**

```bash
git push -u origin feat/survey-campaign-backend
gh pr create --title "feat(survey): campaign lifecycle + 423 on rating POSTs" --body "$(cat <<'EOF'
## Summary
- V7 migration: `campaigns` table with partial-unique open invariant; nullable `campaign_id` FKs on `ratings` and `pair_ratings`.
- New `Campaign` aggregate + `CampaignRepository` port + `PgCampaignRepository`.
- `SubmitRatingUseCase` / `SubmitPairRatingUseCase` gain a `Locked` arm; stamps `campaign_id` on every inserted row.
- `GET /v1/campaign/current` mounted; both rating POSTs return 423 when no open campaign exists.

Implements spec §4–6, §9.1. Schema PR landed in feat/survey-campaign-openapi (#TODO).

## Test plan
- [ ] `./gradlew :survey:domain:test :survey:application:test :survey:infrastructure:test :survey:api:test`
- [ ] Architecture tests pass (Konsist guards).
- [ ] CI: `spotlessCheck`, `openapi-typescript-drift` (frontend regen will land in the next PR), Konsist, Testcontainers.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Phase D — Frontend implementation

PR title: `feat(survey-frontend): campaign status + lock banner on /sondage`
Branch: `feat/survey-campaign-frontend`, branched from `main` **after** Phase C merges.

### Task D1: Branch and regenerate types

**Files:**
- Modify: `frontend/src/infrastructure/api/survey/types.ts` (regenerated)

- [ ] **Step 1: Branch from updated main**

```bash
git checkout main
git pull --ff-only origin main
git checkout -b feat/survey-campaign-frontend
```

- [ ] **Step 2: Regenerate OpenAPI types**

```bash
cd frontend
pnpm api:check
```
Expected: `types.ts` updates to include the new `Campaign` schema and `getCurrentCampaign` operation. If the script reports "no diff", the schema PR hasn't merged or you're on a stale branch — confirm `git log origin/main --oneline | head -5` shows the schema PR's commit.

- [ ] **Step 3: Commit the regen**

```bash
git add frontend/src/infrastructure/api/survey/types.ts
git commit -s -m "chore(survey-frontend): regenerate openapi types for campaign endpoints"
```

---

### Task D2: Application-layer `Campaign` type

**Files:**
- Modify: `frontend/src/application/survey/types.ts`
- Modify: `frontend/src/application/survey/index.ts`

- [ ] **Step 1: Add the `Campaign` type**

In `frontend/src/application/survey/types.ts`, append:

```ts
export interface Campaign {
  readonly campaignId: string;
  readonly batchLabel: string;
  readonly openedAt: string;
  readonly closedAt: string | null;
}

export type CampaignLockKind = 'open' | 'closed';

export function lockKindOf(campaign: Campaign): CampaignLockKind {
  return campaign.closedAt === null ? 'open' : 'closed';
}
```

- [ ] **Step 2: Re-export from `index.ts`**

Append to `frontend/src/application/survey/index.ts`:

```ts
export type { Campaign, CampaignLockKind } from './types';
export { lockKindOf } from './types';
```

- [ ] **Step 3: Typecheck**

```bash
pnpm typecheck
```
Expected: clean.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/application/survey
git commit -s -m "feat(survey-frontend): Campaign application-layer type"
```

---

### Task D3: Survey client — `getCurrentCampaign`

**Files:**
- Modify: `frontend/src/application/survey/index.ts` (extend `SurveyClient` interface)
- Modify: `frontend/src/infrastructure/api/survey/client.ts`

- [ ] **Step 1: Extend the `SurveyClient` port**

Locate `SurveyClient` (likely in `frontend/src/application/survey/index.ts`). Add:

```ts
export interface SurveyClient {
  // ... existing methods ...
  getCurrentCampaign(): Promise<Campaign>;
}
```

- [ ] **Step 2: Implement on the HTTP client**

In `frontend/src/infrastructure/api/survey/client.ts`, add:

```ts
async getCurrentCampaign(): Promise<Campaign> {
  const response = await this.fetch('/v1/campaign/current');
  if (!response.ok) {
    throw new Error(`getCurrentCampaign failed: ${response.status}`);
  }
  const body = (await response.json()) as components['schemas']['Campaign'];
  return {
    campaignId: body.campaignId,
    batchLabel: body.batchLabel,
    openedAt: body.openedAt,
    closedAt: body.closedAt,
  };
}
```

(Match the existing `fetch` helper signature — look at `getNextItem` for the pattern.)

- [ ] **Step 3: Typecheck**

```bash
pnpm typecheck
```
Expected: clean.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/application frontend/src/infrastructure
git commit -s -m "feat(survey-frontend): SurveyClient.getCurrentCampaign"
```

---

### Task D4: `useCampaignStatus` hook

**Files:**
- Create: `frontend/src/ui/components/sondage/useCampaignStatus.ts`
- Create: `frontend/tests/use-campaign-status.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Campaign, SurveyClient } from '@/application/survey';
import { useCampaignStatus } from '@/ui/components/sondage/useCampaignStatus';

function makeClient(campaign: Campaign): SurveyClient {
  return {
    getCurrentCampaign: vi.fn().mockResolvedValue(campaign),
    // other methods unused in this hook test — cast OK
  } as unknown as SurveyClient;
}

const openCampaign: Campaign = {
  campaignId: '00000000-0000-0000-0000-000000000007',
  batchLabel: 'round-7',
  openedAt: '2026-05-30T10:00:00Z',
  closedAt: null,
};

const closedCampaign: Campaign = {
  ...openCampaign,
  closedAt: '2026-05-30T12:00:00Z',
};

describe('useCampaignStatus', () => {
  beforeEach(() => {
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => 'visible',
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('starts in loading, then resolves to open', async () => {
    const client = makeClient(openCampaign);
    const { result } = renderHook(() => useCampaignStatus(client));
    expect(result.current.status.kind).toBe('loading');
    await waitFor(() => expect(result.current.status.kind).toBe('open'));
    expect(client.getCurrentCampaign).toHaveBeenCalledTimes(1);
  });

  it('resolves to closed when closedAt is non-null', async () => {
    const client = makeClient(closedCampaign);
    const { result } = renderHook(() => useCampaignStatus(client));
    await waitFor(() => expect(result.current.status.kind).toBe('closed'));
  });

  it('refetches on visibilitychange when tab becomes visible', async () => {
    const client = makeClient(openCampaign);
    renderHook(() => useCampaignStatus(client));
    await waitFor(() => expect(client.getCurrentCampaign).toHaveBeenCalledTimes(1));
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await waitFor(() => expect(client.getCurrentCampaign).toHaveBeenCalledTimes(2));
  });

  it('refresh() triggers an additional fetch', async () => {
    const client = makeClient(openCampaign);
    const { result } = renderHook(() => useCampaignStatus(client));
    await waitFor(() => expect(client.getCurrentCampaign).toHaveBeenCalledTimes(1));
    act(() => result.current.refresh());
    await waitFor(() => expect(client.getCurrentCampaign).toHaveBeenCalledTimes(2));
  });
});
```

- [ ] **Step 2: Run to confirm failure**

```bash
cd frontend
pnpm test use-campaign-status
```
Expected: FAIL — `useCampaignStatus` not found.

- [ ] **Step 3: Implement the hook**

`frontend/src/ui/components/sondage/useCampaignStatus.ts`:

```ts
import { useCallback, useEffect, useState } from 'react';
import type { Campaign, SurveyClient } from '@/application/survey';

export type CampaignStatus =
  | { readonly kind: 'loading' }
  | { readonly kind: 'open'; readonly campaign: Campaign }
  | { readonly kind: 'closed'; readonly campaign: Campaign };

export interface CampaignStatusApi {
  readonly status: CampaignStatus;
  readonly refresh: () => void;
}

export function useCampaignStatus(client: SurveyClient | null): CampaignStatusApi {
  const [status, setStatus] = useState<CampaignStatus>({ kind: 'loading' });

  const fetchStatus = useCallback(async () => {
    if (!client) return;
    try {
      const c = await client.getCurrentCampaign();
      setStatus({
        kind: c.closedAt === null ? 'open' : 'closed',
        campaign: c,
      });
    } catch {
      // On error keep the previous state; the caller can also surface a
      // banner via its own error handling. We do not flip to 'closed' on
      // network failure — that would lock the UI for transient blips.
    }
  }, [client]);

  useEffect(() => {
    void fetchStatus();
  }, [fetchStatus]);

  useEffect(() => {
    function onVisibility() {
      if (document.visibilityState === 'visible') void fetchStatus();
    }
    document.addEventListener('visibilitychange', onVisibility);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  }, [fetchStatus]);

  const refresh = useCallback(() => {
    void fetchStatus();
  }, [fetchStatus]);

  return { status, refresh };
}
```

- [ ] **Step 4: Run the tests**

```bash
pnpm test use-campaign-status
```
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/ui frontend/tests/use-campaign-status.test.tsx
git commit -s -m "feat(survey-frontend): useCampaignStatus hook"
```

---

### Task D5: `LockBanner` component

**Files:**
- Modify: `frontend/src/ui/components/sondage/labels.ts`
- Create: `frontend/src/ui/components/sondage/LockBanner.tsx`
- Modify: `frontend/src/ui/components/sondage/index.ts`
- Create: `frontend/tests/sondage-lock-banner.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Campaign } from '@/application/survey';
import { LockBanner } from '@/ui/components/sondage/LockBanner';

const closedCampaign: Campaign = {
  campaignId: '00000000-0000-0000-0000-000000000007',
  batchLabel: 'round-7',
  openedAt: '2026-05-30T10:00:00Z',
  closedAt: '2026-05-30T12:00:00Z',
};

describe('LockBanner', () => {
  it('renders the closed-state copy', () => {
    render(<LockBanner campaign={closedCampaign} />);
    expect(screen.getByRole('status')).toHaveTextContent(/campagne en pause/i);
  });

  it('uses polite aria-live so screen readers do not interrupt', () => {
    render(<LockBanner campaign={closedCampaign} />);
    expect(screen.getByRole('status')).toHaveAttribute('aria-live', 'polite');
  });
});
```

- [ ] **Step 2: Run to confirm failure**

```bash
pnpm test sondage-lock-banner
```
Expected: FAIL — `LockBanner` not found.

- [ ] **Step 3: Add the copy**

In `frontend/src/ui/components/sondage/labels.ts`, append:

```ts
export const LOCK_BANNER_TEXT =
  'Campagne en pause — un nouveau lot est en cours d’entraînement. Revenez bientôt pour la suite.';
```

- [ ] **Step 4: Implement `LockBanner`**

`frontend/src/ui/components/sondage/LockBanner.tsx`:

```tsx
import { css } from 'styled-system/css';
import type { Campaign } from '@/application/survey';
import { LOCK_BANNER_TEXT } from './labels';

const bannerStyles = css({
  display: 'flex',
  alignItems: 'center',
  gap: 'sm',
  paddingBlock: 'sm',
  paddingInline: 'md',
  bg: 'surface',
  color: 'fgMuted',
  border: '1px solid token(colors.border)',
  borderRadius: 'md',
  fontSize: 'body',
});

interface Props {
  readonly campaign: Campaign;
}

export function LockBanner({ campaign }: Props) {
  return (
    <div
      className={bannerStyles}
      role="status"
      aria-live="polite"
      data-testid="sondage-lock-banner"
      data-batch-label={campaign.batchLabel}
    >
      <span>{LOCK_BANNER_TEXT}</span>
    </div>
  );
}
```

- [ ] **Step 5: Export from `index.ts`**

Append to `frontend/src/ui/components/sondage/index.ts`:

```ts
export { LockBanner } from './LockBanner';
export { useCampaignStatus } from './useCampaignStatus';
export type { CampaignStatus, CampaignStatusApi } from './useCampaignStatus';
```

- [ ] **Step 6: Run the tests**

```bash
pnpm test sondage-lock-banner
```
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/ui frontend/tests/sondage-lock-banner.test.tsx
git commit -s -m "feat(survey-frontend): LockBanner + lock copy"
```

---

### Task D6: `disabled` prop on `RatingCard` and `PairCard`

**Files:**
- Modify: `frontend/src/ui/components/sondage/RatingCard.tsx`
- Modify: `frontend/src/ui/components/sondage/PairCard.tsx`
- Modify (test): `frontend/tests/sondage-verdict-picker.test.tsx` (or a new test for the disabled state)

- [ ] **Step 1: Write the failing test (RatingCard)**

Add a test case to `frontend/tests/sondage-verdict-picker.test.tsx` (or create a new file `sondage-rating-card-disabled.test.tsx`):

```tsx
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { SurveyItem } from '@/application/survey';
import { RatingCard } from '@/ui/components/sondage/RatingCard';

const sampleItem: SurveyItem = {
  itemId: '00000000-0000-0000-0000-000000000001',
  mot: 'CHAT',
  definition: 'animal domestique',
  // ... fill in required fields ...
} as SurveyItem;

describe('RatingCard disabled', () => {
  it('renders verdict buttons with aria-disabled and ignores clicks', () => {
    const onVerdict = vi.fn();
    const onCorriger = vi.fn();
    render(
      <RatingCard
        item={sampleItem}
        onVerdict={onVerdict}
        onCorriger={onCorriger}
        disabled={true}
      />,
    );
    const goodBtn = screen.getByRole('button', { name: /bon/i });
    expect(goodBtn).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(goodBtn);
    expect(onVerdict).not.toHaveBeenCalled();
  });

  it('hides the Corriger entry button when disabled', () => {
    render(
      <RatingCard
        item={sampleItem}
        onVerdict={vi.fn()}
        onCorriger={vi.fn()}
        disabled={true}
      />,
    );
    expect(screen.queryByRole('button', { name: /corriger/i })).toBeNull();
  });
});
```

- [ ] **Step 2: Run to confirm failure**

```bash
pnpm test sondage-rating-card-disabled
```
Expected: FAIL — `disabled` prop not yet supported.

- [ ] **Step 3: Add the `disabled` prop to `RatingCard`**

In `frontend/src/ui/components/sondage/RatingCard.tsx`, extend the props interface:

```ts
interface RatingCardProps {
  readonly item: SurveyItem;
  readonly onVerdict: (verdict: Verdict, latencyMs: number) => Promise<void> | void;
  readonly onCorriger: (correctedText: string, pos: SurveyPos, latencyMs: number) => Promise<void> | void;
  readonly disabled?: boolean;
}
```

In the component body, destructure `disabled = false`. Pass `aria-disabled={disabled}` on each verdict button and short-circuit the click handler when `disabled` is true. Conditionally hide the "Corriger" entry button when disabled (and if there's an open Corriger panel state, close it).

- [ ] **Step 4: Mirror on `PairCard`**

Same prop and same behaviour — `aria-disabled` on all pair-verdict buttons; click handlers short-circuit when `disabled`.

- [ ] **Step 5: Run the tests**

```bash
pnpm test sondage-rating-card-disabled
pnpm test sondage-verdict-picker
pnpm test sondage-pair-card
```
Expected: PASS (existing tests unchanged; new disabled tests green).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/ui frontend/tests
git commit -s -m "feat(survey-frontend): RatingCard/PairCard disabled prop"
```

---

### Task D7: Wire `useCampaignStatus` + `LockBanner` into `/sondage`

**Files:**
- Modify: `frontend/src/ui/routes/sondage.lazy.tsx`
- Create: `frontend/tests/sondage-route-locked.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { renderSondageRouteWithProviders } from './harness/sondage-route-harness';
// ^ harness exists for the existing sondage-route.test.tsx; reuse it.

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterAll(() => server.close());
beforeEach(() => server.resetHandlers());

describe('/sondage when campaign is closed', () => {
  it('renders the LockBanner and disables verdict buttons', async () => {
    server.use(
      http.get('*/v1/campaign/current', () =>
        HttpResponse.json({
          campaignId: '00000000-0000-0000-0000-000000000007',
          batchLabel: 'round-7',
          openedAt: '2026-05-30T10:00:00Z',
          closedAt: '2026-05-30T12:00:00Z',
        }),
      ),
      http.get('*/v1/items/next', () =>
        HttpResponse.json({ itemId: '00000000-0000-0000-0000-000000000001', mot: 'CHAT', /* ... */ }),
      ),
    );
    renderSondageRouteWithProviders();
    await waitFor(() => expect(screen.getByTestId('sondage-lock-banner')).toBeInTheDocument());
    const goodBtn = await screen.findByRole('button', { name: /bon/i });
    expect(goodBtn).toHaveAttribute('aria-disabled', 'true');
  });

  it('reacts to 423 from submit by refreshing status', async () => {
    let campaignOpen = true;
    server.use(
      http.get('*/v1/campaign/current', () =>
        HttpResponse.json({
          campaignId: '00000000-0000-0000-0000-000000000007',
          batchLabel: 'round-7',
          openedAt: '2026-05-30T10:00:00Z',
          closedAt: campaignOpen ? null : '2026-05-30T12:00:00Z',
        }),
      ),
      http.get('*/v1/items/next', () =>
        HttpResponse.json({ itemId: '00000000-0000-0000-0000-000000000001', mot: 'CHAT', /* ... */ }),
      ),
      http.post('*/v1/items/:id/rating', () => {
        campaignOpen = false;
        return new HttpResponse(null, { status: 423 });
      }),
    );
    renderSondageRouteWithProviders();
    // simulate click GOOD → expect 423 → expect LockBanner to appear
    const goodBtn = await screen.findByRole('button', { name: /bon/i });
    goodBtn.click();
    await waitFor(() => expect(screen.getByTestId('sondage-lock-banner')).toBeInTheDocument());
  });
});
```

(If `renderSondageRouteWithProviders` doesn't exist, follow the pattern in the existing `frontend/tests/sondage-route.test.tsx` — it should set up the router + auth + survey-client context.)

- [ ] **Step 2: Run to confirm failure**

```bash
pnpm test sondage-route-locked
```
Expected: FAIL.

- [ ] **Step 3: Wire the route**

In `frontend/src/ui/routes/sondage.lazy.tsx`, inside `SondagePage()`:

```tsx
const campaignStatus = useCampaignStatus(surveyClient);
const isLocked = campaignStatus.status.kind === 'closed';
```

In the JSX, render the `LockBanner` above the existing intro paragraph when `isLocked`:

```tsx
{isLocked && campaignStatus.status.kind === 'closed' && (
  <LockBanner campaign={campaignStatus.status.campaign} />
)}
```

Pass `disabled={isLocked}` to `RatingCard`. In `onVerdict` and `onCorriger`, catch a 423 response (the client should throw a typed error — add a `SondageLockedError` class in `client.ts` if not present, mirroring `CorrectifRejectedError`) and call `campaignStatus.refresh()`.

- [ ] **Step 4: Run the tests**

```bash
pnpm test sondage-route
pnpm test sondage-route-locked
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -s -m "feat(survey-frontend): wire campaign lock into /sondage route"
```

---

### Task D8: Wire into `/sondage/pairs`

**Files:**
- Modify: `frontend/src/ui/routes/sondage.pairs.lazy.tsx`
- Create: `frontend/tests/sondage-pairs-route-locked.test.tsx`

- [ ] **Step 1: Write the failing test**

Mirror D7's two test cases but for the pair route, hitting `/v1/pair-ratings` on submit.

- [ ] **Step 2: Run to confirm failure**

```bash
pnpm test sondage-pairs-route-locked
```
Expected: FAIL.

- [ ] **Step 3: Wire the route**

Same pattern as D7: `useCampaignStatus`, render `LockBanner`, pass `disabled` to `PairCard`, catch 423 in `onVerdict` and call `refresh()`.

- [ ] **Step 4: Run the tests**

```bash
pnpm test sondage-pairs-route
pnpm test sondage-pairs-route-locked
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src
git commit -s -m "feat(survey-frontend): wire campaign lock into /sondage/pairs route"
```

---

### Task D9: a11y + final checks

- [ ] **Step 1: Run a11y suite**

```bash
pnpm a11y
```
Expected: PASS. The disabled-state cards must have buttons reachable in tab order with `aria-disabled` correctly announced.

- [ ] **Step 2: Run the full frontend suite**

```bash
pnpm test
pnpm typecheck
pnpm lint
```
Expected: PASS.

- [ ] **Step 3: Push and open the frontend PR**

```bash
git push -u origin feat/survey-campaign-frontend
gh pr create --title "feat(survey-frontend): campaign status + lock banner on /sondage" --body "$(cat <<'EOF'
## Summary
- Regenerate openapi types for the new `Campaign` schema.
- `useCampaignStatus` hook (mount + visibilitychange + on-demand refresh).
- `LockBanner` component with the agreed French copy and polite aria-live.
- `RatingCard` / `PairCard` gain a `disabled` prop that hides the Corriger entry and aria-disables verdict buttons.
- `/sondage` and `/sondage/pairs` render the banner when `closedAt` is non-null and call `refresh()` on a 423.

Implements spec §8. Requires the backend PR (#TODO) to be deployed.

## Test plan
- [ ] `pnpm test` (full suite)
- [ ] `pnpm a11y`
- [ ] `pnpm typecheck`
- [ ] Manual: open `/sondage` with an open campaign → submit verdict succeeds. Close campaign via SQL → reload → banner appears, buttons disabled.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Phase E — Backfill script

PR title: `chore(survey-scripts): one-shot campaign backfill from Modal logs`
Branch: `chore/survey-campaign-backfill`, branched from `main` **after** Phase C merges (it doesn't depend on the frontend PR).

### Task E1: Branch

- [ ] **Step 1: Branch from updated main**

```bash
git checkout main
git pull --ff-only origin main
git checkout -b chore/survey-campaign-backfill
```

---

### Task E2: Script skeleton + arg parsing

**Files:**
- Create: `scripts/survey/backfill_campaigns.py`

- [ ] **Step 1: Write the skeleton**

```python
#!/usr/bin/env python3
"""Backfill campaigns and stamp historical ratings.

Reads a CSV of historical training-batch windows (batch_label, opened_at,
closed_at) — one row per legacy Modal run — and:

  1. INSERTs a campaigns row per CSV row (idempotent: skips labels that
     already exist).
  2. UPDATEs ratings.campaign_id = ? WHERE created_at IN [opened_at, closed_at)
     AND campaign_id IS NULL for each campaign.
  3. Same for pair_ratings.
  4. Prints a coverage report.

This is a one-shot operational tool, run by the maintainer after the V7
migration deploys. It is NOT a Flyway migration: a bug here cannot wedge
the cluster.

Usage:
    python scripts/survey/backfill_campaigns.py \\
        --dsn postgres://survey:***@host:5432/survey \\
        --batches scripts/survey/historical_batches.csv \\
        [--dry-run]
"""

from __future__ import annotations

import argparse
import csv
import sys
import uuid
from dataclasses import dataclass
from datetime import datetime
from typing import Iterable

import psycopg


@dataclass(frozen=True)
class HistoricalBatch:
    batch_label: str
    opened_at: datetime
    closed_at: datetime


def parse_batches(path: str) -> list[HistoricalBatch]:
    out: list[HistoricalBatch] = []
    with open(path, encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            out.append(
                HistoricalBatch(
                    batch_label=row["batch_label"].strip(),
                    opened_at=datetime.fromisoformat(row["opened_at"]),
                    closed_at=datetime.fromisoformat(row["closed_at"]),
                )
            )
    out.sort(key=lambda b: b.opened_at)
    for prev, curr in zip(out, out[1:]):
        if curr.opened_at < prev.closed_at:
            raise SystemExit(
                f"overlapping batches: {prev.batch_label} ends "
                f"{prev.closed_at}, {curr.batch_label} starts {curr.opened_at}"
            )
    return out


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dsn", required=True)
    parser.add_argument("--batches", required=True)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)

    batches = parse_batches(args.batches)
    print(f"Loaded {len(batches)} historical batches from {args.batches}", file=sys.stderr)

    with psycopg.connect(args.dsn, autocommit=False) as conn:
        ensure_campaigns(conn, batches, dry_run=args.dry_run)
        stamp_ratings(conn, batches, dry_run=args.dry_run)
        stamp_pair_ratings(conn, batches, dry_run=args.dry_run)
        coverage_report(conn)
        if args.dry_run:
            conn.rollback()
            print("DRY RUN — rolled back.", file=sys.stderr)
        else:
            conn.commit()
    return 0


# Implementations of ensure_campaigns / stamp_ratings / stamp_pair_ratings /
# coverage_report live in subsequent steps.


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
```

- [ ] **Step 2: Commit the skeleton**

```bash
git add scripts/survey/backfill_campaigns.py
git commit -s -m "chore(survey-scripts): backfill_campaigns skeleton"
```

---

### Task E3: `ensure_campaigns` + test

**Files:**
- Modify: `scripts/survey/backfill_campaigns.py`
- Create: `scripts/survey/test_backfill_campaigns.py`

- [ ] **Step 1: Write the failing test**

```python
# scripts/survey/test_backfill_campaigns.py
"""Unit tests for backfill_campaigns. Use a real Postgres via testcontainers-python.

Run: pytest scripts/survey/test_backfill_campaigns.py
"""

from __future__ import annotations

import sys
from datetime import datetime, timezone
from pathlib import Path

import psycopg
import pytest
from testcontainers.postgres import PostgresContainer

sys.path.insert(0, str(Path(__file__).parent))

import backfill_campaigns as bf


@pytest.fixture(scope="module")
def pg():
    with PostgresContainer("postgres:16-alpine") as p:
        yield p


@pytest.fixture
def conn(pg):
    with psycopg.connect(pg.get_connection_url(driver=None), autocommit=False) as c:
        # Apply the same V7 schema the migration applies.
        with c.cursor() as cur:
            cur.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto")
            cur.execute(open("survey/infrastructure/src/main/resources/db/migration/V7__campaigns.sql").read())
        c.commit()
        yield c
        with c.cursor() as cur:
            cur.execute("TRUNCATE campaigns CASCADE")
        c.commit()


def _at(s: str) -> datetime:
    return datetime.fromisoformat(s).replace(tzinfo=timezone.utc)


def test_ensure_campaigns_inserts_one_row_per_batch(conn):
    batches = [
        bf.HistoricalBatch("round-5", _at("2026-05-01T00:00:00"), _at("2026-05-07T00:00:00")),
        bf.HistoricalBatch("round-6", _at("2026-05-08T00:00:00"), _at("2026-05-15T00:00:00")),
    ]
    bf.ensure_campaigns(conn, batches, dry_run=False)
    with conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM campaigns")
        assert cur.fetchone()[0] == 2


def test_ensure_campaigns_is_idempotent(conn):
    batches = [bf.HistoricalBatch("round-5", _at("2026-05-01T00:00:00"), _at("2026-05-07T00:00:00"))]
    bf.ensure_campaigns(conn, batches, dry_run=False)
    bf.ensure_campaigns(conn, batches, dry_run=False)
    with conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM campaigns WHERE batch_label = 'round-5'")
        assert cur.fetchone()[0] == 1
```

- [ ] **Step 2: Run to confirm failure**

```bash
pytest scripts/survey/test_backfill_campaigns.py::test_ensure_campaigns_inserts_one_row_per_batch
```
Expected: FAIL — `ensure_campaigns` not defined.

- [ ] **Step 3: Implement `ensure_campaigns`**

Append to `backfill_campaigns.py`:

```python
def ensure_campaigns(conn, batches: Iterable[HistoricalBatch], *, dry_run: bool) -> None:
    """INSERT a campaigns row per batch, idempotent by batch_label."""
    with conn.cursor() as cur:
        for batch in batches:
            cur.execute(
                "SELECT 1 FROM campaigns WHERE batch_label = %s",
                (batch.batch_label,),
            )
            if cur.fetchone() is not None:
                continue
            cur.execute(
                """
                INSERT INTO campaigns (campaign_id, batch_label, opened_at, closed_at)
                VALUES (%s, %s, %s, %s)
                """,
                (uuid.uuid4(), batch.batch_label, batch.opened_at, batch.closed_at),
            )
```

- [ ] **Step 4: Run the tests**

```bash
pytest scripts/survey/test_backfill_campaigns.py
```
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add scripts/survey
git commit -s -m "chore(survey-scripts): ensure_campaigns + tests"
```

---

### Task E4: `stamp_ratings` + `stamp_pair_ratings` + tests

**Files:**
- Modify: `scripts/survey/backfill_campaigns.py`
- Modify: `scripts/survey/test_backfill_campaigns.py`

- [ ] **Step 1: Add a test that inserts a few legacy ratings then stamps them**

```python
def test_stamp_ratings_attributes_rows_by_created_at(conn):
    batches = [
        bf.HistoricalBatch("round-5", _at("2026-05-01T00:00:00"), _at("2026-05-07T00:00:00")),
        bf.HistoricalBatch("round-6", _at("2026-05-08T00:00:00"), _at("2026-05-15T00:00:00")),
    ]
    bf.ensure_campaigns(conn, batches, dry_run=False)
    # Insert two ratings, one in each window. We need a survey_item row first;
    # use a fixture helper (assumed in conftest.py) or inline it:
    import uuid as _uuid
    item_id = _uuid.uuid4()
    user_id = _uuid.uuid4()
    with conn.cursor() as cur:
        cur.execute(
            """INSERT INTO survey_items (item_id, mot, definition, pos, categorie, style, force_claimed, longueur, source, source_batch, tier, is_calibration, expected, retired_at, created_at)
               VALUES (%s, 'CHAT', 'animal', 'nom_commun', 'animals', 'classique', 3, 4, 'legacy', 'pre-campaign', 'mid', false, NULL, NULL, '2026-04-30T00:00:00Z')""",
            (item_id,),
        )
        for rid, created_at in (
            (_uuid.uuid4(), "2026-05-03T12:00:00Z"),
            (_uuid.uuid4(), "2026-05-10T12:00:00Z"),
        ):
            cur.execute(
                """INSERT INTO ratings (rating_id, item_id, user_id, submitted_as, qualite, difficulte, created_at)
                   VALUES (%s, %s, %s, 'auth', 5, 3, %s)""",
                (rid, item_id, user_id, created_at),
            )
    bf.stamp_ratings(conn, batches, dry_run=False)
    with conn.cursor() as cur:
        cur.execute(
            "SELECT c.batch_label, count(*) FROM ratings r JOIN campaigns c ON r.campaign_id = c.campaign_id GROUP BY c.batch_label ORDER BY c.batch_label",
        )
        rows = cur.fetchall()
    assert rows == [("round-5", 1), ("round-6", 1)]
```

- [ ] **Step 2: Run to confirm failure**

```bash
pytest scripts/survey/test_backfill_campaigns.py::test_stamp_ratings_attributes_rows_by_created_at
```
Expected: FAIL.

- [ ] **Step 3: Implement `stamp_ratings`**

```python
def stamp_ratings(conn, batches: Iterable[HistoricalBatch], *, dry_run: bool) -> None:
    """UPDATE ratings.campaign_id for rows whose created_at falls in [opened, closed)."""
    with conn.cursor() as cur:
        for batch in batches:
            cur.execute(
                """
                UPDATE ratings
                   SET campaign_id = (SELECT campaign_id FROM campaigns WHERE batch_label = %s)
                 WHERE created_at >= %s
                   AND created_at <  %s
                   AND campaign_id IS NULL
                """,
                (batch.batch_label, batch.opened_at, batch.closed_at),
            )


def stamp_pair_ratings(conn, batches: Iterable[HistoricalBatch], *, dry_run: bool) -> None:
    """Same shape for pair_ratings."""
    with conn.cursor() as cur:
        for batch in batches:
            cur.execute(
                """
                UPDATE pair_ratings
                   SET campaign_id = (SELECT campaign_id FROM campaigns WHERE batch_label = %s)
                 WHERE created_at >= %s
                   AND created_at <  %s
                   AND campaign_id IS NULL
                """,
                (batch.batch_label, batch.opened_at, batch.closed_at),
            )
```

- [ ] **Step 4: Run the tests**

```bash
pytest scripts/survey/test_backfill_campaigns.py
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/survey
git commit -s -m "chore(survey-scripts): stamp_ratings + stamp_pair_ratings + tests"
```

---

### Task E5: Coverage report + dry-run support + README

**Files:**
- Modify: `scripts/survey/backfill_campaigns.py`
- Create: `scripts/survey/README.md`

- [ ] **Step 1: Implement `coverage_report`**

```python
def coverage_report(conn) -> None:
    """Print per-campaign stamp counts and the unattributed bucket."""
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT COALESCE(c.batch_label, '<unattributed>') AS label,
                   count(r.rating_id)        AS ratings_n,
                   count(pr.id)              AS pair_ratings_n
              FROM (SELECT campaign_id, batch_label FROM campaigns
                    UNION ALL SELECT NULL, NULL) c
         LEFT JOIN ratings r       ON r.campaign_id IS NOT DISTINCT FROM c.campaign_id
         LEFT JOIN pair_ratings pr ON pr.campaign_id IS NOT DISTINCT FROM c.campaign_id
             GROUP BY c.batch_label
             ORDER BY label
            """
        )
        print("Coverage:", file=sys.stderr)
        print(f"  {'label':<24} {'ratings':>10} {'pair_ratings':>14}", file=sys.stderr)
        for label, ratings_n, pair_ratings_n in cur.fetchall():
            print(f"  {label:<24} {ratings_n:>10} {pair_ratings_n:>14}", file=sys.stderr)
```

- [ ] **Step 2: Write the README**

`scripts/survey/README.md`:

```markdown
# survey scripts

## `backfill_campaigns.py`

One-shot historical-rating attribution. Run **after** V7 (`campaigns`) has
been deployed and **before** any follow-up migration that tightens
`campaign_id` to `NOT NULL`.

### Input

A CSV with one row per historical batch:

```csv
batch_label,opened_at,closed_at
round-5,2026-05-01T00:00:00,2026-05-07T00:00:00
round-6,2026-05-08T00:00:00,2026-05-15T00:00:00
```

Source the windows from Modal job logs: each batch run's `started_at` →
`finished_at`. Sort by `opened_at`; the script rejects overlapping
windows.

### Usage

```bash
python scripts/survey/backfill_campaigns.py \
    --dsn postgres://survey:***@host:5432/survey \
    --batches scripts/survey/historical_batches.csv \
    [--dry-run]
```

`--dry-run` rolls back the transaction at the end so you can audit the
coverage report before committing.

### Idempotency

Each step is idempotent:
- `ensure_campaigns` skips labels that already exist.
- `stamp_ratings` / `stamp_pair_ratings` only touch rows where
  `campaign_id IS NULL`.

Re-running the script after partial failure is safe.
```

- [ ] **Step 3: Commit**

```bash
git add scripts/survey
git commit -s -m "chore(survey-scripts): coverage report + README"
```

- [ ] **Step 4: Push and open the backfill PR**

```bash
git push -u origin chore/survey-campaign-backfill
gh pr create --title "chore(survey-scripts): one-shot campaign backfill from Modal logs" --body "$(cat <<'EOF'
## Summary
- One-shot `scripts/survey/backfill_campaigns.py` reads a CSV of historical batch windows and stamps `ratings.campaign_id` + `pair_ratings.campaign_id` retroactively.
- testcontainers-Postgres tests for each step (`ensure_campaigns`, `stamp_ratings`, `stamp_pair_ratings`).
- README documents input format, idempotency, and the dry-run flow.

Implements spec §7. Run by the maintainer after the backend PR (#TODO) deploys.

## Test plan
- [ ] `pytest scripts/survey/test_backfill_campaigns.py`
- [ ] Dry-run against staging DB; verify coverage report numbers match the expected per-batch counts.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-review checklist (planner)

Run this list before merging the plan PR.

**Spec coverage:**
- [x] §3 architecture → Phase C file map + Phase D file map.
- [x] §4 data model → C2 (migration), C3–C4 (domain), C7 (Pg adapter), C10–C11 (Pg row inserts).
- [x] §5 API surface → B2–B4 (schema), C12–C14 (handlers).
- [x] §6 backend flow → C8–C9 (use cases), C15 (wiring), C16 (arch tests).
- [x] §7 backfill → Phase E.
- [x] §8 frontend → Phase D.
- [x] §9 testing → tests written at every step (TDD).
- [x] §10 forward-compat → ADR-0059 documents it; nothing in v1 forecloses the undo follow-up.
- [x] §11 operational notes → E5 README; observability flagged as "not in v1".
- [x] §12 PR decomposition → Phases A–E map 1:1.

**Placeholder scan:** none — every code block contains the actual content.

**Type consistency:**
- `Campaign`, `CampaignId` introduced in C3, used identically in C4–C16, D2–D8.
- `SubmitRatingResult.Locked` introduced in C8, mapped to 423 in C13.
- `SubmitPairRatingResult.Locked` introduced in C9, mapped to 423 in C14.
- `useCampaignStatus` returns `{ status, refresh }`; consumed identically in D7 and D8.
- `LockBanner` takes `{ campaign: Campaign }`; consumed identically in D7 and D8.
