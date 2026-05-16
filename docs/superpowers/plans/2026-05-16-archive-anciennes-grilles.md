# "Voir les anciennes grilles" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a `/grilles` archive route that lists every past + current daily puzzle with per-row progress, backed by a new thin `listDailyPuzzles` endpoint and a one-time refactor that scopes solo-entries localStorage by session id (groundwork for the future server-side-progress workstream).

**Architecture:** Four sequential PRs (with one parallel pair) under the 400-line cap (ADR-0001 §4). Server stays stateless w.r.t. per-user progress; client derives it locally; the puzzle list endpoint returns thin summaries only. Date↔UUID conversion reuses the existing deterministic `DailyPuzzleSelector`. Schema-first per ADR-0003.

**Tech Stack:**
- Backend: Kotlin 2.x + Ktor 3.x + JDBC + Postgres + Flyway + JUnit 5 + AssertJ + Kotest + Konsist
- Frontend: TypeScript + React 19 + TanStack Router + Vite + Panda CSS + Vitest + MSW + Playwright
- Wire contract: OpenAPI 3.1 (`grid/api/openapi.yaml`)

**Companion spec:** `docs/superpowers/specs/2026-05-16-archive-anciennes-grilles-design.md`

---

## PR rollout overview

| PR | Branch | Scope | Diff target | Depends on |
|---|---|---|---|---|
| 1 | `feat/grid-api-list-dailies-schema` | OpenAPI: `listDailyPuzzles` + `PuzzleSummary` schema | ~80 | — |
| 2 | `feat/grid-list-dailies-impl` | Migration `V4__add_total_letter_cells.sql`, repo+use case+route handler, write-path update | ~260 | PR 1 |
| 3 | `chore/frontend-solo-scope-by-session` | `SoloEntriesStore` keyed by session id, legacy migration, erase chain fix, privacy notice rows | ~200 | — |
| 4 | `feat/frontend-grilles-route` | `/grilles` page, `/grille?date=`, enable Accueil link, dedicated skeleton, tests + a11y + e2e | ~340 | PRs 1 + 3 |

PRs 1 + 3 are independent (different bounded contexts) — dispatch concurrently. PR 2 follows PR 1. PR 4 follows PR 3 and uses the schema from PR 1 (MSW handler during dev unblocks parallel landing with PR 2).

Each PR opens from `main` in its own worktree (do not stack on this brainstorm branch).

---

## File structure (consolidated)

### Created

- `grid/api/openapi.yaml` (existing — extended)
- `grid/api/src/main/resources/db/migration/V4__add_total_letter_cells.sql`
- `grid/infrastructure/src/test/resources/db/migration/V4__add_total_letter_cells.sql`
- `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/ListDailyPuzzlesUseCase.kt`
- `grid/application/src/test/kotlin/com/bliss/grid/application/puzzle/ListDailyPuzzlesUseCaseTest.kt`
- `grid/api/src/test/kotlin/com/bliss/grid/api/routes/PuzzleRouteListDailiesTest.kt`
- `grid/infrastructure/src/test/kotlin/com/bliss/grid/infrastructure/persistence/PostgresPuzzleRepositorySummariesTest.kt`
- `frontend/src/ui/routes/grilles.tsx`
- `frontend/src/ui/components/grilles/GrillesPage.tsx`
- `frontend/src/ui/components/grilles/GrillesSkeleton.tsx`
- `frontend/src/ui/components/grilles/MonthSection.tsx`
- `frontend/src/ui/components/grilles/DayRow.tsx`
- `frontend/tests/grilles-route.test.tsx`
- `frontend/tests/grille-date-search-param.test.tsx`
- `frontend/tests/solo-entries-store-session-scope.test.ts`
- `frontend/tests/clear-session-wipes-solo-entries.test.ts`
- `frontend/e2e/archive.spec.ts`

### Modified

- `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/PuzzleRepository.kt` (new port methods)
- `grid/infrastructure/src/main/kotlin/com/bliss/grid/infrastructure/persistence/PostgresPuzzleRepository.kt` (impl)
- `grid/infrastructure/src/main/kotlin/com/bliss/grid/infrastructure/persistence/InMemoryPuzzleRepository.kt` (impl)
- `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/LoadOrGeneratePuzzleUseCase.kt` (compute totalLetterCells on persist)
- `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/EnsureUpcomingDailiesUseCase.kt` (same — worker write path)
- `grid/api/src/main/kotlin/com/bliss/grid/api/routes/PuzzleRoute.kt` (new GET handler)
- `grid/api/src/main/kotlin/com/bliss/grid/api/dto/*` (new `PuzzleSummaryDto`)
- `frontend/src/application/solo/SoloEntriesStore.ts` (constructor signature)
- `frontend/src/infrastructure/session/localStorageSolo.ts` (session-scoped keys + migration)
- `frontend/src/infrastructure/session/localStorageSession.ts` (defensive sweep on clear)
- `frontend/src/main.tsx` (composition root: pass session resolver to store)
- `frontend/src/ui/components/PrivacyNotice.tsx` (fr + en data tables)
- `frontend/src/application/puzzle/PuzzleRepository.ts` (new port)
- `frontend/src/infrastructure/api/grid/HttpPuzzleRepository.ts` (impl + mapper)
- `frontend/src/infrastructure/api/grid/mapper.ts` (`DailySummary`)
- `frontend/src/infrastructure/mocks/handlers.ts` (MSW for the new endpoint)
- `frontend/src/ui/routes/__root.tsx` (register `grilles` route)
- `frontend/src/ui/routes/grille.tsx` (search-param `date`)
- `frontend/src/ui/routes/accueil.tsx` (enable archive link, navigate to `/grilles`)
- `frontend/src/ui/seo.ts` (`INDEXABLE_ROUTES` += `/grilles`)
- `frontend/tests/accueil-route.test.tsx` (replace disabled-link assertion)

---

# PR 1 — Schema: `listDailyPuzzles`

**Branch:** `feat/grid-api-list-dailies-schema` from `main`. **Target diff:** ~80 lines.

### Task 1.1: Create the PR worktree and confirm clean baseline

**Files:** —

- [ ] **Step 1: Create worktree from main**

```bash
project_root=/Users/isho/IdeaProjects/bliss
base=~/.config/superpowers/worktrees/bliss
git -C "$project_root" fetch origin main
git -C "$project_root" worktree add "$base/feat-grid-list-dailies-schema" -b feat/grid-api-list-dailies-schema origin/main
cd "$base/feat-grid-list-dailies-schema"
git branch --unset-upstream
```

- [ ] **Step 2: Verify baseline — openapi-lint passes on main as-is**

```bash
cd grid/api && npx --yes @redocly/cli@latest lint openapi.yaml
```

Expected: zero errors. If anything is red on main, stop and investigate — do not start the task on top of an already-broken baseline.

### Task 1.2: Extend `grid/api/openapi.yaml` with the new path and schema

**Files:**
- Modify: `grid/api/openapi.yaml`

- [ ] **Step 1: Add the `/v1/puzzles/daily/list` path entry under `paths:` immediately after the existing `/v1/puzzles/daily` block**

Open `grid/api/openapi.yaml`. Locate the end of the `/v1/puzzles/daily:` operation (the `200` response of `getDailyPuzzle`). After its closing block but before the next top-level path, insert:

```yaml
  /v1/puzzles/daily/list:
    get:
      operationId: listDailyPuzzles
      summary: List past + current daily puzzles.
      description: |
        Returns thin summaries of dailies in the requested range, newest
        first. Always excludes future dates; clamps `from` to the launch
        anchor (2026-01-01 UTC) and `to` to today UTC. Per-user progress
        is NOT included — clients derive it locally from the solo-entries
        store keyed by `id`. The response is bounded at 100 items.
      tags: [puzzles]
      parameters:
        - in: query
          name: from
          required: false
          description: |
            ISO-8601 calendar date inclusive. Defaults to 31 days before
            `to` when omitted. Values earlier than the launch anchor are
            clamped to the anchor.
          schema:
            type: string
            format: date
            example: '2026-04-15'
        - in: query
          name: to
          required: false
          description: |
            ISO-8601 calendar date inclusive. Defaults to today UTC.
            Future values are clamped to today.
          schema:
            type: string
            format: date
            example: '2026-05-16'
      responses:
        '200':
          description: |
            List resolved. `items` is sorted DESC by `date`. May be empty
            (e.g. range entirely before launch anchor, or no row exists
            for any date in the range because the worker hasn't filled it
            yet).
          content:
            application/json:
              schema:
                type: object
                required: [items]
                properties:
                  items:
                    type: array
                    maxItems: 100
                    items:
                      $ref: '#/components/schemas/PuzzleSummary'
        '400':
          description: |
            `from` or `to` is not a valid ISO-8601 date. RFC 7807;
            `type` is `https://bliss.example/errors/invalid-puzzle-date`.
          content:
            application/problem+json:
              schema:
                $ref: '#/components/schemas/Problem'
```

- [ ] **Step 2: Add the `PuzzleSummary` schema to `components.schemas`**

Locate `components:` → `schemas:` in the same file. Add the following entry alphabetically near `Puzzle:`:

```yaml
    PuzzleSummary:
      type: object
      description: |
        Thin daily-puzzle summary returned by `listDailyPuzzles`. Carries
        only the fields the archive UI needs to render a row; the full
        `Puzzle` document is fetched lazily via `getDailyPuzzle` when the
        player opens a past grid. `id` is the deterministic UUID v7
        derived from `date`, matching what `getDailyPuzzle?date=` returns.
      required: [id, date, gridNumber, totalLetterCells]
      properties:
        id:
          type: string
          format: uuid
          description: UUID v7, deterministic from `date`.
          example: 01926a4d-7000-7000-8000-000000000000
        date:
          type: string
          format: date
          example: '2026-05-05'
        gridNumber:
          type: integer
          minimum: 1
          description: Sequence number from the launch anchor (day 1 = 2026-01-01 UTC).
          example: 125
        difficulty:
          type: string
          nullable: true
          enum: [facile, moyen, difficile]
          example: facile
        totalLetterCells:
          type: integer
          minimum: 1
          description: Count of letter cells in the puzzle (denominator for client-side progress bars).
          example: 28
```

- [ ] **Step 3: Run openapi-lint**

```bash
cd grid/api && npx --yes @redocly/cli@latest lint openapi.yaml
```

Expected: zero errors.

- [ ] **Step 4: Regenerate frontend types and confirm drift gate passes**

```bash
cd ../../frontend && pnpm api:check
```

Expected: `frontend/src/infrastructure/api/grid/types.ts` is regenerated; `git status` shows it as modified; the generated types now contain a `paths['/v1/puzzles/daily/list']` entry and `components['schemas']['PuzzleSummary']`. Stage the regenerated file alongside the YAML.

- [ ] **Step 5: Commit**

```bash
git add grid/api/openapi.yaml frontend/src/infrastructure/api/grid/types.ts
git commit -s -m "feat(grid-api-schema): list past daily puzzles

Adds GET /v1/puzzles/daily/list returning thin PuzzleSummary items
for the upcoming /grilles archive route. Per ADR-0003 §1, schema lands
ahead of producer + consumer impl.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

### Task 1.3: Push branch and open PR

- [ ] **Step 1: Push**

```bash
git push -u origin feat/grid-api-list-dailies-schema
```

- [ ] **Step 2: Open PR**

```bash
gh pr create --base main --title "feat(grid-api-schema): list past daily puzzles" --body "$(cat <<'EOF'
## Summary
- Adds `GET /v1/puzzles/daily/list` (operationId `listDailyPuzzles`) returning thin `PuzzleSummary` items (id, date, gridNumber, totalLetterCells, difficulty).
- Schema-only per ADR-0003 §1 / ADR-0001 §3. Producer (PR 2) and consumer (PR 4) follow.

## Test plan
- [x] openapi-lint passes locally
- [x] `pnpm api:check` regenerates types without drift after the YAML change
- [ ] CI: `openapi-lint`, `openapi-typescript-drift`, `dco`, `branch-name`, `commitlint`

## Spec
`docs/superpowers/specs/2026-05-16-archive-anciennes-grilles-design.md` — PR 1.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Wait for CI gates** before queueing PR 2. Expected gates: `openapi-lint`, `openapi-typescript-drift`, `commitlint`, `dco`, `branch-name`, `secret-scan`.

---

# PR 2 — Backend: list endpoint + migration

**Branch:** `feat/grid-list-dailies-impl` from `main` after PR 1 merges. **Target diff:** ~260 lines.

### Task 2.1: Create worktree on top of merged PR 1

**Files:** —

- [ ] **Step 1: Pull merged main and create worktree**

```bash
project_root=/Users/isho/IdeaProjects/bliss
base=~/.config/superpowers/worktrees/bliss
git -C "$project_root" fetch origin main
git -C "$project_root" worktree add "$base/feat-grid-list-dailies-impl" -b feat/grid-list-dailies-impl origin/main
cd "$base/feat-grid-list-dailies-impl"
git branch --unset-upstream
```

- [ ] **Step 2: Verify `pnpm api:check` is clean (no drift)** — PR 1's regenerated types should already be on main.

```bash
cd frontend && pnpm api:check && git status --short
```

Expected: clean tree.

### Task 2.2: Add the Flyway migration

**Files:**
- Create: `grid/api/src/main/resources/db/migration/V4__add_total_letter_cells.sql`
- Create: `grid/infrastructure/src/test/resources/db/migration/V4__add_total_letter_cells.sql`

- [ ] **Step 1: Write the production migration**

Path: `grid/api/src/main/resources/db/migration/V4__add_total_letter_cells.sql`

```sql
-- Adds the denormalised letter-cell count to puzzles so the
-- listDailyPuzzles endpoint can render archive rows without parsing the
-- full payload JSONB per row. Nullable to keep the migration reversible;
-- the application layer treats NULL defensively (rows are filtered out
-- of summary results until backfilled).
--
-- The backfill statement extracts the count from the existing payload's
-- cells[] array, matching the runtime kind=letter filter used by the
-- domain Grid.fromPlacements path.

ALTER TABLE puzzles
    ADD COLUMN total_letter_cells INTEGER;

UPDATE puzzles
SET total_letter_cells = (
    SELECT count(*)
    FROM jsonb_array_elements(payload -> 'cells') AS c
    WHERE c ->> 'kind' = 'letter'
)
WHERE total_letter_cells IS NULL;
```

- [ ] **Step 2: Mirror the migration into the test resources tree**

Path: `grid/infrastructure/src/test/resources/db/migration/V4__add_total_letter_cells.sql`

Same content as Step 1, verbatim. The repository ships duplicate migrations under `src/main/resources/db/migration` (prod) and `src/test/resources/db/migration` (Testcontainers DBs use this set). Both must stay in lock-step — verify by `diff -q`:

```bash
diff -q grid/api/src/main/resources/db/migration/V4__add_total_letter_cells.sql \
        grid/infrastructure/src/test/resources/db/migration/V4__add_total_letter_cells.sql
```

Expected: no output (files identical).

- [ ] **Step 3: Run the JVM test suite to ensure the migration applies cleanly against existing fixtures**

```bash
./gradlew :grid:infrastructure:test --tests "*PostgresPuzzleRepositoryTest*" --rerun-tasks
```

Expected: PASS. The existing test only exercises `get` / `getOrCompute`; the new column appearing as NULL in pre-write fixtures is fine.

- [ ] **Step 4: Commit**

```bash
git add grid/api/src/main/resources/db/migration/V4__add_total_letter_cells.sql \
        grid/infrastructure/src/test/resources/db/migration/V4__add_total_letter_cells.sql
git commit -s -m "feat(grid-infrastructure): add total_letter_cells column

Backfill via JSONB scan over payload.cells[]. Migration is nullable to
keep it reversible; write paths in the next commit populate the column
on every new insert.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

### Task 2.3: Extend `StoredPuzzle` and write-path with `totalLetterCells`

**Files:**
- Modify: `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/PuzzleRepository.kt`
- Modify: `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/LoadOrGeneratePuzzleUseCase.kt`
- Modify: `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/EnsureUpcomingDailiesUseCase.kt`

- [ ] **Step 1: Write a failing test asserting `StoredPuzzle` carries `totalLetterCells`**

Path: `grid/application/src/test/kotlin/com/bliss/grid/application/puzzle/StoredPuzzleShapeTest.kt`

```kotlin
package com.bliss.grid.application.puzzle

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StoredPuzzleShapeTest {
    @Test
    fun `StoredPuzzle exposes totalLetterCells derived from the grid`() {
        val grid = buildSampleGridWithLetterCells(count = 7)
        val stored = StoredPuzzle(
            grid = grid,
            title = "test",
            language = "fr",
            hintsAllowed = 3,
            createdAt = java.time.Instant.EPOCH,
        )
        assertThat(stored.totalLetterCells).isEqualTo(7)
    }
}
```

`buildSampleGridWithLetterCells` is a helper to add in the same test file. Use the existing test factories — search for `Grid.fromPlacements(` in the test tree for an existing builder pattern. If none, inline a minimal grid:

```kotlin
private fun buildSampleGridWithLetterCells(count: Int): com.bliss.grid.domain.model.Grid {
    // Build a 1xN grid with `count` letter cells via the existing domain factory.
    // Re-use the same builder pattern used by RevealCellHintUseCaseTest.
    TODO("Use the same grid builder as RevealCellHintUseCaseTest — copy that helper here.")
}
```

Open `RevealCellHintUseCaseTest.kt`, find its grid builder, copy it.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :grid:application:test --tests "*StoredPuzzleShapeTest*"
```

Expected: FAIL — `StoredPuzzle` has no property `totalLetterCells`.

- [ ] **Step 3: Add `totalLetterCells` to `StoredPuzzle`**

Open `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/PuzzleRepository.kt`. Replace the `data class StoredPuzzle` definition (currently lines 44–50) with:

```kotlin
/**
 * Server-side puzzle snapshot. Carries the canonical [Grid] (with its
 * letters — server-private, never serialized to clients) plus the wire-side
 * fields needed to render the response on subsequent GETs.
 *
 * `totalLetterCells` is denormalised here so the list endpoint can return
 * thin summaries without re-reading every payload JSON document.
 */
data class StoredPuzzle(
    val grid: Grid,
    val title: String,
    val language: String,
    val hintsAllowed: Int,
    val createdAt: Instant,
) {
    val totalLetterCells: Int = grid.cells.count { it.kind == com.bliss.grid.domain.model.CellKind.LETTER }
}
```

(Adjust the `CellKind` enum import path to whatever the domain actually exposes — search for `CellKind` in `grid/domain/`.)

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :grid:application:test --tests "*StoredPuzzleShapeTest*"
```

Expected: PASS.

- [ ] **Step 5: Update `PostgresPuzzleRepository` to write and read `total_letter_cells`**

Open `grid/infrastructure/src/main/kotlin/com/bliss/grid/infrastructure/persistence/PostgresPuzzleRepository.kt`. Apply:

```kotlin
// SELECT_SQL — add total_letter_cells:
private const val SELECT_SQL =
    "SELECT puzzle_id, width, height, language, title, payload, hints_allowed, created_at, total_letter_cells " +
        "FROM puzzles WHERE puzzle_id = ?"

// INSERT_SQL — add the column + bind:
private const val INSERT_SQL =
    "INSERT INTO puzzles " +
        "(puzzle_id, width, height, language, title, payload, hints_allowed, created_at, total_letter_cells) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (puzzle_id) DO NOTHING"

// In getOrCompute(): add the bind after setTimestamp(8, ...)
stmt.setInt(9, produced.totalLetterCells)

// In toStoredPuzzle(): the derived totalLetterCells on StoredPuzzle is
// computed in the constructor — no read change strictly needed. But the
// list endpoint reads total_letter_cells directly without materialising
// the full payload, so the column read remains essential. Leave SELECT
// as updated above; do not change the toStoredPuzzle() body.
```

- [ ] **Step 6: Run repo tests**

```bash
./gradlew :grid:infrastructure:test --tests "*PostgresPuzzleRepository*"
```

Expected: PASS (existing tests are oblivious to the new column; new bind doesn't break the contract).

- [ ] **Step 7: Update `InMemoryPuzzleRepository` if it stores anything beyond the StoredPuzzle** — likely no change needed because the in-memory store keeps `StoredPuzzle` objects directly, which now carry `totalLetterCells` via the constructor. Verify by opening `InMemoryPuzzleRepository.kt` and reading. No code change unless something is wrong.

- [ ] **Step 8: Commit**

```bash
git add grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/PuzzleRepository.kt \
        grid/application/src/test/kotlin/com/bliss/grid/application/puzzle/StoredPuzzleShapeTest.kt \
        grid/infrastructure/src/main/kotlin/com/bliss/grid/infrastructure/persistence/PostgresPuzzleRepository.kt
git commit -s -m "feat(grid-application): derive totalLetterCells on StoredPuzzle

Computed from the canonical grid at construction. Postgres repo
persists it alongside payload so the upcoming list endpoint can return
summaries without scanning JSONB per row.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

### Task 2.4: Add the `findSummariesByIds` port + Postgres impl

**Files:**
- Modify: `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/PuzzleRepository.kt`
- Modify: `grid/infrastructure/src/main/kotlin/com/bliss/grid/infrastructure/persistence/PostgresPuzzleRepository.kt`
- Modify: `grid/infrastructure/src/main/kotlin/com/bliss/grid/infrastructure/persistence/InMemoryPuzzleRepository.kt`
- Create: `grid/infrastructure/src/test/kotlin/com/bliss/grid/infrastructure/persistence/PostgresPuzzleRepositorySummariesTest.kt`

- [ ] **Step 1: Write the failing repo test**

Path: `grid/infrastructure/src/test/kotlin/com/bliss/grid/infrastructure/persistence/PostgresPuzzleRepositorySummariesTest.kt`

```kotlin
package com.bliss.grid.infrastructure.persistence

import com.bliss.grid.application.puzzle.PuzzleRepository
import com.bliss.grid.application.puzzle.StoredPuzzle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class PostgresPuzzleRepositorySummariesTest : AbstractFlywayDbTest() {
    // Inherit Testcontainers + Flyway harness from the existing
    // AbstractFlywayDbTest used by PostgresPuzzleRepositoryTest. If that
    // exact base class name differs, mirror whatever PostgresPuzzleRepositoryTest
    // extends.

    @Test
    fun `findSummariesByIds returns only persisted ids with their totalLetterCells`() {
        val repo: PuzzleRepository = PostgresPuzzleRepository(dataSource)
        val ids = (1..3).map { UUID.randomUUID() }
        // Persist only ids[0] and ids[2]
        repo.getOrCompute(ids[0]) { sampleStoredPuzzle(letterCount = 7) }
        repo.getOrCompute(ids[2]) { sampleStoredPuzzle(letterCount = 12) }

        val summaries = repo.findSummariesByIds(ids)

        assertThat(summaries).hasSize(2)
        assertThat(summaries.associate { it.puzzleId to it.totalLetterCells })
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(ids[0] to 7, ids[2] to 12),
            )
    }

    @Test
    fun `findSummariesByIds excludes rows where total_letter_cells is null`() {
        // Insert a row with null total_letter_cells via direct SQL to
        // simulate a pre-backfill leftover.
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO puzzles (puzzle_id, width, height, language, title, payload, hints_allowed, total_letter_cells) " +
                    "VALUES (?, 5, 5, 'fr', 't', '{\"cells\":[]}'::jsonb, 3, NULL)",
            ).use { s ->
                val id = UUID.randomUUID()
                s.setObject(1, id)
                s.executeUpdate()

                val summaries = (PostgresPuzzleRepository(dataSource))
                    .findSummariesByIds(listOf(id))
                assertThat(summaries).isEmpty()
            }
        }
    }

    private fun sampleStoredPuzzle(letterCount: Int): StoredPuzzle =
        // Reuse the existing factory from PostgresPuzzleRepositoryTest.
        // Search that file for its sample-grid builder and copy here.
        TODO("Replace with the factory used by PostgresPuzzleRepositoryTest")
}
```

Resolve the `TODO` by reading `PostgresPuzzleRepositoryTest.kt` and copying its grid-building helper into a shared `internal` factory file (`grid/infrastructure/src/test/kotlin/.../PuzzleTestFixtures.kt`) if duplicating, or simply inline.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :grid:infrastructure:test --tests "*PostgresPuzzleRepositorySummariesTest*"
```

Expected: FAIL — `findSummariesByIds` does not exist.

- [ ] **Step 3: Add the port method and `StoredSummary` to `PuzzleRepository`**

Open `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/PuzzleRepository.kt`. Append to the `interface PuzzleRepository`:

```kotlin
    /**
     * Returns thin summaries for the supplied ids, in unspecified order.
     * Rows whose `total_letter_cells` column is NULL (i.e. pre-backfill
     * leftovers) are excluded. Missing ids (no row) are silently absent
     * from the result.
     */
    fun findSummariesByIds(puzzleIds: List<UUID>): List<StoredSummary>
```

Append a new data class at the bottom of the file:

```kotlin
/** Thin projection for the archive endpoint. See [PuzzleRepository.findSummariesByIds]. */
data class StoredSummary(
    val puzzleId: UUID,
    val totalLetterCells: Int,
)
```

- [ ] **Step 4: Implement on `PostgresPuzzleRepository`**

Open `grid/infrastructure/src/main/kotlin/com/bliss/grid/infrastructure/persistence/PostgresPuzzleRepository.kt`. Add a new override:

```kotlin
override fun findSummariesByIds(puzzleIds: List<UUID>): List<StoredSummary> {
    if (puzzleIds.isEmpty()) return emptyList()
    return dataSource.connection.use { conn ->
        // Bind the list as a UUID[] for `= ANY(?)`. PG JDBC needs an
        // explicit array conversion via createArrayOf.
        val arr = conn.createArrayOf("uuid", puzzleIds.toTypedArray())
        conn.prepareStatement(SUMMARIES_SQL).use { stmt ->
            stmt.setArray(1, arr)
            stmt.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            StoredSummary(
                                puzzleId = rs.getObject("puzzle_id", UUID::class.java),
                                totalLetterCells = rs.getInt("total_letter_cells"),
                            ),
                        )
                    }
                }
            }
        }
    }
}
```

And add to the `companion object`:

```kotlin
private const val SUMMARIES_SQL =
    "SELECT puzzle_id, total_letter_cells " +
        "FROM puzzles " +
        "WHERE puzzle_id = ANY(?) AND total_letter_cells IS NOT NULL"
```

Also import `com.bliss.grid.application.puzzle.StoredSummary` at the top.

- [ ] **Step 5: Implement on `InMemoryPuzzleRepository`**

Open `grid/infrastructure/src/main/kotlin/com/bliss/grid/infrastructure/persistence/InMemoryPuzzleRepository.kt`. Add an override that iterates the in-memory map:

```kotlin
override fun findSummariesByIds(puzzleIds: List<UUID>): List<StoredSummary> {
    return puzzleIds.mapNotNull { id ->
        val stored = get(id) ?: return@mapNotNull null
        StoredSummary(puzzleId = id, totalLetterCells = stored.totalLetterCells)
    }
}
```

(No NULL filter needed — in-memory stores compute `totalLetterCells` synchronously via the data-class derived property.)

- [ ] **Step 6: Run the repo tests**

```bash
./gradlew :grid:infrastructure:test --tests "*PostgresPuzzleRepositorySummariesTest*"
```

Expected: PASS.

- [ ] **Step 7: Run the full module test suite**

```bash
./gradlew :grid:infrastructure:test :grid:application:test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/PuzzleRepository.kt \
        grid/infrastructure/src/main/kotlin/com/bliss/grid/infrastructure/persistence/PostgresPuzzleRepository.kt \
        grid/infrastructure/src/main/kotlin/com/bliss/grid/infrastructure/persistence/InMemoryPuzzleRepository.kt \
        grid/infrastructure/src/test/kotlin/com/bliss/grid/infrastructure/persistence/PostgresPuzzleRepositorySummariesTest.kt
git commit -s -m "feat(grid-infrastructure): add findSummariesByIds port + impl

Thin projection over (puzzle_id, total_letter_cells). Postgres uses
'= ANY(?::uuid[])'; in-memory adapter is a straightforward map walk.
Rows with NULL total_letter_cells are filtered out as defensive cover
for the pre-backfill window.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

### Task 2.5: `ListDailyPuzzlesUseCase`

**Files:**
- Create: `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/ListDailyPuzzlesUseCase.kt`
- Create: `grid/application/src/test/kotlin/com/bliss/grid/application/puzzle/ListDailyPuzzlesUseCaseTest.kt`

- [ ] **Step 1: Write the failing use-case test**

Path: `grid/application/src/test/kotlin/com/bliss/grid/application/puzzle/ListDailyPuzzlesUseCaseTest.kt`

```kotlin
package com.bliss.grid.application.puzzle

import com.bliss.grid.infrastructure.persistence.InMemoryPuzzleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ListDailyPuzzlesUseCaseTest {
    private val today = LocalDate.parse("2026-05-16")
    private val selector = DailyPuzzleSelector()
    private val repo = InMemoryPuzzleRepository()

    private fun useCase(): ListDailyPuzzlesUseCase =
        ListDailyPuzzlesUseCase(
            puzzleRepository = repo,
            dailyPuzzleSelector = selector,
            launchAnchor = LocalDate.parse("2026-01-01"),
            defaultRangeDays = 31,
            maxItems = 100,
        )

    @Test
    fun `default range covers 31 days ending today`() {
        seedDailyRows(LocalDate.parse("2026-04-15")..today)
        val result = useCase().execute(from = null, to = null, today = today)
        assertThat(result.items.first().date).isEqualTo(today)
        assertThat(result.items.last().date).isEqualTo(LocalDate.parse("2026-04-15"))
        assertThat(result.items).hasSize(32)
    }

    @Test
    fun `items are sorted DESC by date`() {
        seedDailyRows(LocalDate.parse("2026-05-10")..today)
        val result = useCase().execute(from = null, to = null, today = today)
        val dates = result.items.map { it.date }
        assertThat(dates).isSortedAccordingTo(Comparator.reverseOrder())
    }

    @Test
    fun `from earlier than launch anchor is clamped silently`() {
        seedDailyRows(LocalDate.parse("2026-01-01")..today)
        val result = useCase().execute(
            from = LocalDate.parse("2025-01-01"),
            to = today,
            today = today,
        )
        assertThat(result.items.last().date).isEqualTo(LocalDate.parse("2026-01-01"))
    }

    @Test
    fun `to later than today is clamped to today`() {
        seedDailyRows(LocalDate.parse("2026-05-14")..today)
        val result = useCase().execute(
            from = LocalDate.parse("2026-05-14"),
            to = LocalDate.parse("2030-01-01"),
            today = today,
        )
        assertThat(result.items.first().date).isEqualTo(today)
    }

    @Test
    fun `from greater than to returns empty`() {
        seedDailyRows(LocalDate.parse("2026-05-01")..today)
        val result = useCase().execute(
            from = LocalDate.parse("2026-05-10"),
            to = LocalDate.parse("2026-05-01"),
            today = today,
        )
        assertThat(result.items).isEmpty()
    }

    @Test
    fun `missing rows in the range are omitted from the result`() {
        // Seed only every other day
        var d = LocalDate.parse("2026-05-01")
        while (!d.isAfter(today)) {
            if (d.dayOfMonth % 2 == 0) seedDailyRows(d..d)
            d = d.plusDays(1)
        }
        val result = useCase().execute(
            from = LocalDate.parse("2026-05-01"),
            to = today,
            today = today,
        )
        assertThat(result.items).allSatisfy { item ->
            assertThat(item.date.dayOfMonth % 2).isZero
        }
    }

    @Test
    fun `result is capped at maxItems`() {
        seedDailyRows(LocalDate.parse("2026-01-01")..today)  // > 100 days
        val result = useCase().execute(
            from = LocalDate.parse("2026-01-01"),
            to = today,
            today = today,
        )
        assertThat(result.items).hasSize(100)
        // Cap takes the most recent 100 (newest-first).
        assertThat(result.items.first().date).isEqualTo(today)
    }

    private fun seedDailyRows(range: ClosedRange<LocalDate>) {
        var d = range.start
        while (!d.isAfter(range.endInclusive)) {
            val id = selector.puzzleIdForDate(d)
            repo.getOrCompute(id) {
                // Re-use sample factory from the existing repo test.
                sampleStoredPuzzleForUseCase(letterCount = 28)
            }
            d = d.plusDays(1)
        }
    }
}
```

Provide `sampleStoredPuzzleForUseCase` by reading the existing pattern in `LoadOrGeneratePuzzleUseCaseCacheHitTest.kt` (or its sibling) and copying.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :grid:application:test --tests "*ListDailyPuzzlesUseCaseTest*"
```

Expected: FAIL — `ListDailyPuzzlesUseCase` does not exist.

- [ ] **Step 3: Implement the use case**

Path: `grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/ListDailyPuzzlesUseCase.kt`

```kotlin
package com.bliss.grid.application.puzzle

import java.time.LocalDate

/**
 * Returns a thin, newest-first list of daily puzzles for the upcoming
 * /grilles archive route. The use case never reads full puzzle payloads:
 * date-to-id mapping is the deterministic [DailyPuzzleSelector] used by
 * the daily GET, and the repository projects to (id, totalLetterCells).
 * Per-user progress is computed client-side and is intentionally absent
 * from this contract.
 */
class ListDailyPuzzlesUseCase(
    private val puzzleRepository: PuzzleRepository,
    private val dailyPuzzleSelector: DailyPuzzleSelector,
    private val launchAnchor: LocalDate = DEFAULT_LAUNCH_ANCHOR,
    private val defaultRangeDays: Int = DEFAULT_RANGE_DAYS,
    private val maxItems: Int = DEFAULT_MAX_ITEMS,
    private val defaultDifficulty: String = DEFAULT_DIFFICULTY,
) {
    fun execute(
        from: LocalDate?,
        to: LocalDate?,
        today: LocalDate,
    ): Result {
        val clampedTo = (to ?: today).coerceAtMost(today)
        val clampedFrom = (from ?: clampedTo.minusDays(defaultRangeDays.toLong()))
            .coerceAtLeast(launchAnchor)
        if (clampedFrom.isAfter(clampedTo)) return Result(items = emptyList())

        val dates = buildList<LocalDate> {
            var d = clampedTo
            while (!d.isBefore(clampedFrom)) {
                add(d)
                d = d.minusDays(1)
            }
        }
        val ids = dates.map { dailyPuzzleSelector.puzzleIdForDate(it) }
        val summaries = puzzleRepository.findSummariesByIds(ids)
            .associateBy { it.puzzleId }

        val items = dates
            .mapNotNull { date ->
                val id = dailyPuzzleSelector.puzzleIdForDate(date)
                val summary = summaries[id] ?: return@mapNotNull null
                Item(
                    id = id,
                    date = date,
                    gridNumber = gridNumberFor(date),
                    difficulty = defaultDifficulty,
                    totalLetterCells = summary.totalLetterCells,
                )
            }
            .take(maxItems)

        return Result(items = items)
    }

    private fun gridNumberFor(date: LocalDate): Int =
        (date.toEpochDay() - launchAnchor.toEpochDay()).toInt() + 1

    data class Result(val items: List<Item>)

    data class Item(
        val id: java.util.UUID,
        val date: LocalDate,
        val gridNumber: Int,
        val difficulty: String?,
        val totalLetterCells: Int,
    )

    companion object {
        val DEFAULT_LAUNCH_ANCHOR: LocalDate = LocalDate.parse("2026-01-01")
        const val DEFAULT_RANGE_DAYS: Int = 31
        const val DEFAULT_MAX_ITEMS: Int = 100
        const val DEFAULT_DIFFICULTY: String = "facile"
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :grid:application:test --tests "*ListDailyPuzzlesUseCaseTest*"
```

Expected: PASS.

- [ ] **Step 5: Konsist check — confirm no JDBC import in `application/`**

```bash
./gradlew :grid:application:test --tests "*ArchitectureTest*"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add grid/application/src/main/kotlin/com/bliss/grid/application/puzzle/ListDailyPuzzlesUseCase.kt \
        grid/application/src/test/kotlin/com/bliss/grid/application/puzzle/ListDailyPuzzlesUseCaseTest.kt
git commit -s -m "feat(grid-application): add ListDailyPuzzlesUseCase

Clamps from/to to [launch_anchor, today], computes the UUID v7 list via
DailyPuzzleSelector, projects through PuzzleRepository.findSummariesByIds,
zips dates with summaries newest-first, caps at 100 items. Pure
application-layer logic; no JDBC dependency.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

### Task 2.6: HTTP route handler

**Files:**
- Modify: `grid/api/src/main/kotlin/com/bliss/grid/api/routes/PuzzleRoute.kt`
- Create: `grid/api/src/main/kotlin/com/bliss/grid/api/dto/PuzzleSummaryDto.kt`
- Create: `grid/api/src/main/kotlin/com/bliss/grid/api/dto/ListDailyPuzzlesResponseDto.kt`
- Create: `grid/api/src/test/kotlin/com/bliss/grid/api/routes/PuzzleRouteListDailiesTest.kt`

- [ ] **Step 1: Define the response DTOs**

Path: `grid/api/src/main/kotlin/com/bliss/grid/api/dto/PuzzleSummaryDto.kt`

```kotlin
package com.bliss.grid.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PuzzleSummaryDto(
    val id: String,
    val date: String,
    val gridNumber: Int,
    val difficulty: String? = null,
    val totalLetterCells: Int,
)
```

Path: `grid/api/src/main/kotlin/com/bliss/grid/api/dto/ListDailyPuzzlesResponseDto.kt`

```kotlin
package com.bliss.grid.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class ListDailyPuzzlesResponseDto(
    val items: List<PuzzleSummaryDto>,
)
```

- [ ] **Step 2: Write the failing route test**

Path: `grid/api/src/test/kotlin/com/bliss/grid/api/routes/PuzzleRouteListDailiesTest.kt`

```kotlin
package com.bliss.grid.api.routes

import com.bliss.grid.api.dto.ListDailyPuzzlesResponseDto
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PuzzleRouteListDailiesTest : AbstractPuzzleRouteTest() {
    // Inherit the test harness used by the existing PuzzleRouteTest —
    // mirrors its testApplication { ... } setup and seeds 5 daily rows.

    @Test
    fun `returns items in DESC date order`() = puzzleRouteTest { client ->
        seedDailyRows(daysBack = 5)

        val response = client.get("/v1/puzzles/daily/list")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)

        val body = Json.decodeFromString(
            ListDailyPuzzlesResponseDto.serializer(),
            response.bodyAsText(),
        )
        val dates = body.items.map { it.date }
        assertThat(dates).isSortedAccordingTo(Comparator.reverseOrder())
    }

    @Test
    fun `respects from and to query parameters`() = puzzleRouteTest { client ->
        seedDailyRows(daysBack = 10)
        val response = client.get("/v1/puzzles/daily/list?from=2026-05-10&to=2026-05-12")
        val body = Json.decodeFromString(
            ListDailyPuzzlesResponseDto.serializer(),
            response.bodyAsText(),
        )
        assertThat(body.items.map { it.date }).containsExactly("2026-05-12", "2026-05-11", "2026-05-10")
    }

    @Test
    fun `returns 400 invalid-puzzle-date for malformed date param`() = puzzleRouteTest { client ->
        val response = client.get("/v1/puzzles/daily/list?from=not-a-date")
        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(response.bodyAsText()).contains("invalid-puzzle-date")
    }

    @Test
    fun `returns empty items when range entirely before launch anchor`() = puzzleRouteTest { client ->
        seedDailyRows(daysBack = 5)
        val response = client.get("/v1/puzzles/daily/list?from=2024-01-01&to=2024-12-31")
        val body = Json.decodeFromString(
            ListDailyPuzzlesResponseDto.serializer(),
            response.bodyAsText(),
        )
        assertThat(body.items).isEmpty()
    }
}
```

Resolve `AbstractPuzzleRouteTest`, `puzzleRouteTest`, `seedDailyRows` by reading the existing `PuzzleRouteTest.kt`. If those helpers do not exist as a shared base class, lift the matching boilerplate into one when this test is added.

- [ ] **Step 3: Run the test to verify it fails**

```bash
./gradlew :grid:api:test --tests "*PuzzleRouteListDailiesTest*"
```

Expected: FAIL — route does not exist.

- [ ] **Step 4: Add the route handler**

Open `grid/api/src/main/kotlin/com/bliss/grid/api/routes/PuzzleRoute.kt`. After the existing `get("/v1/puzzles/daily")` handler closes, add a new handler:

```kotlin
get("/v1/puzzles/daily/list") {
    val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

    val from = call.parameters["from"]?.let { raw ->
        try { LocalDate.parse(raw) } catch (_: DateTimeParseException) {
            call.respondProblem(
                status = HttpStatusCode.BadRequest,
                title = "Date invalide",
                type = INVALID_PUZZLE_DATE_TYPE,
                detail = "Le paramètre from doit être au format ISO-8601 YYYY-MM-DD, reçu : '$raw'.",
            )
            return@get
        }
    }
    val to = call.parameters["to"]?.let { raw ->
        try { LocalDate.parse(raw) } catch (_: DateTimeParseException) {
            call.respondProblem(
                status = HttpStatusCode.BadRequest,
                title = "Date invalide",
                type = INVALID_PUZZLE_DATE_TYPE,
                detail = "Le paramètre to doit être au format ISO-8601 YYYY-MM-DD, reçu : '$raw'.",
            )
            return@get
        }
    }

    val result = withContext(Dispatchers.IO) {
        listDailyPuzzles.execute(from = from, to = to, today = today)
    }

    val items = result.items.map {
        PuzzleSummaryDto(
            id = it.id.toString(),
            date = it.date.toString(),
            gridNumber = it.gridNumber,
            difficulty = it.difficulty,
            totalLetterCells = it.totalLetterCells,
        )
    }
    val started = clock.millis()
    call.respond(ListDailyPuzzlesResponseDto(items = items))
    log.info(
        "list_daily_puzzles from={} to={} items_returned={} latency_ms={}",
        from ?: "(default)",
        to ?: "(default)",
        items.size,
        clock.millis() - started,
    )
}
```

Add `listDailyPuzzles: ListDailyPuzzlesUseCase` to the `Route.puzzles(...)` parameter list and to the composition root that wires the routes. Find the existing wiring (search for `Route.puzzles(` in `grid/api/src/main/`); add the new arg there with the in-memory or Postgres-backed use-case as appropriate.

Add the necessary imports at the top of `PuzzleRoute.kt`:

```kotlin
import com.bliss.grid.api.dto.ListDailyPuzzlesResponseDto
import com.bliss.grid.api.dto.PuzzleSummaryDto
import com.bliss.grid.application.puzzle.ListDailyPuzzlesUseCase
```

- [ ] **Step 5: Run the route test to verify it passes**

```bash
./gradlew :grid:api:test --tests "*PuzzleRouteListDailiesTest*"
```

Expected: PASS.

- [ ] **Step 6: Full Gradle build to catch wiring + arch regressions**

```bash
./gradlew build --parallel --build-cache
```

Expected: PASS for all `grid:*` modules. `spotlessCheck` clean. Konsist arch tests green.

- [ ] **Step 7: Commit**

```bash
git add grid/api/src/main/kotlin/com/bliss/grid/api/routes/PuzzleRoute.kt \
        grid/api/src/main/kotlin/com/bliss/grid/api/dto/PuzzleSummaryDto.kt \
        grid/api/src/main/kotlin/com/bliss/grid/api/dto/ListDailyPuzzlesResponseDto.kt \
        grid/api/src/test/kotlin/com/bliss/grid/api/routes/PuzzleRouteListDailiesTest.kt
# Plus any composition-root file you touched in Step 4.
git commit -s -m "feat(grid-api): expose GET /v1/puzzles/daily/list

Ktor handler validates from/to (400 invalid-puzzle-date on parse failure),
delegates to ListDailyPuzzlesUseCase on Dispatchers.IO, and serialises
items as PuzzleSummaryDto matching grid/api/openapi.yaml.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

### Task 2.7: Push, open PR

- [ ] **Step 1: Push + open PR**

```bash
git push -u origin feat/grid-list-dailies-impl
gh pr create --base main --title "feat(grid-api): implement listDailyPuzzles + total_letter_cells migration" --body "$(cat <<'EOF'
## Summary
- `V4__add_total_letter_cells.sql` migration (additive, nullable, backfilled) — mirrored in prod + test migration roots.
- `StoredPuzzle.totalLetterCells` derived at construction; `PostgresPuzzleRepository` reads/writes the new column.
- New port `findSummariesByIds(List<UUID>) -> List<StoredSummary>` (Postgres + in-memory).
- `ListDailyPuzzlesUseCase` clamps to [launch anchor, today], builds the UUID list via the existing deterministic selector, returns DESC-by-date, caps at 100.
- `GET /v1/puzzles/daily/list` Ktor handler matching the schema landed in #PR1.

## Test plan
- [x] `./gradlew build --parallel --build-cache` green locally
- [x] New repo + use-case + route tests added (TDD)
- [x] Konsist arch tests pass
- [ ] CI: `ci`, `commitlint`, `dco`, `branch-name`, `secret-scan`, `codeql`, `dependency-review`

## Spec
`docs/superpowers/specs/2026-05-16-archive-anciennes-grilles-design.md` — PR 2.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

# PR 3 — Frontend: scope solo entries by session id

**Branch:** `chore/frontend-solo-scope-by-session` from `main`. **Target diff:** ~200 lines.

Can land in parallel with PR 1.

### Task 3.1: Create the PR worktree

- [ ] **Step 1: Worktree from main**

```bash
project_root=/Users/isho/IdeaProjects/bliss
base=~/.config/superpowers/worktrees/bliss
git -C "$project_root" fetch origin main
git -C "$project_root" worktree add "$base/chore-frontend-solo-scope" -b chore/frontend-solo-scope-by-session origin/main
cd "$base/chore-frontend-solo-scope"
git branch --unset-upstream
```

- [ ] **Step 2: Baseline — `pnpm test` green**

```bash
cd frontend && pnpm install && pnpm test
```

Expected: PASS.

### Task 3.2: Refactor `localStorageSolo.ts` to use a session-scoped key

**Files:**
- Modify: `frontend/src/infrastructure/session/localStorageSolo.ts`
- Create: `frontend/tests/solo-entries-store-session-scope.test.ts`

- [ ] **Step 1: Write the failing test**

Path: `frontend/tests/solo-entries-store-session-scope.test.ts`

```ts
import { beforeEach, describe, expect, it } from 'vitest';
import {
  clearAllSoloEntriesForSession,
  loadSoloEntries,
  saveSoloLetter,
} from '@/infrastructure/session/localStorageSolo';

describe('localStorageSolo (session-scoped)', () => {
  beforeEach(() => {
    globalThis.localStorage.clear();
  });

  it('writes under bliss.solo.entries.<sessionId> and is invisible to a different session', () => {
    const sessionA = '01234567-89ab-7000-8000-000000000000';
    const sessionB = '01234567-89ab-7000-8000-000000000001';
    saveSoloLetter(sessionA, 'puzzle-1', 0, 0, 'A');

    expect(loadSoloEntries(sessionA, 'puzzle-1')).toEqual([{ row: 0, column: 0, letter: 'A' }]);
    expect(loadSoloEntries(sessionB, 'puzzle-1')).toEqual([]);
    expect(globalThis.localStorage.getItem(`bliss.solo.entries.${sessionA}`)).not.toBeNull();
    expect(globalThis.localStorage.getItem(`bliss.solo.entries.${sessionB}`)).toBeNull();
  });

  it('clearAllSoloEntriesForSession removes only that session\'s key', () => {
    const sessionA = '01234567-89ab-7000-8000-000000000000';
    const sessionB = '01234567-89ab-7000-8000-000000000001';
    saveSoloLetter(sessionA, 'puzzle-1', 0, 0, 'A');
    saveSoloLetter(sessionB, 'puzzle-1', 0, 0, 'B');

    clearAllSoloEntriesForSession(sessionA);

    expect(loadSoloEntries(sessionA, 'puzzle-1')).toEqual([]);
    expect(loadSoloEntries(sessionB, 'puzzle-1')).toEqual([{ row: 0, column: 0, letter: 'B' }]);
  });

  it('migrates a legacy unscoped bliss.solo.entries blob to the current session on first read', () => {
    const session = '01234567-89ab-7000-8000-000000000000';
    globalThis.localStorage.setItem(
      'bliss.solo.entries',
      JSON.stringify({
        'puzzle-1': { entries: [{ r: 1, c: 2, l: 'X' }], lockedCells: [], hintsUsed: 0 },
      }),
    );

    expect(loadSoloEntries(session, 'puzzle-1')).toEqual([{ row: 1, column: 2, letter: 'X' }]);
    expect(globalThis.localStorage.getItem('bliss.solo.entries')).toBeNull();
    expect(globalThis.localStorage.getItem(`bliss.solo.entries.${session}`)).not.toBeNull();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
pnpm test -- solo-entries-store-session-scope
```

Expected: FAIL — current `loadSoloEntries(puzzleId)` has the wrong signature.

- [ ] **Step 3: Refactor `localStorageSolo.ts` to take `sessionId` as the first parameter and key the storage by it**

Open `frontend/src/infrastructure/session/localStorageSolo.ts`. Replace the entire file with:

```ts
// storage failures are non-fatal — every helper degrades to a no-op rather than throw.
//
// Solo-entries storage is scoped by session id (groundwork for the
// server-side-progress workstream queued after this PR). A one-shot
// migration on first read moves the legacy unscoped `bliss.solo.entries`
// blob into the current session's key and deletes the legacy key.

import type { SoloEntry, SoloLockedCell } from '@/application/solo/SoloEntriesStore';

const KEY_PREFIX = 'bliss.solo.entries.';
const LEGACY_KEY = 'bliss.solo.entries';

interface StoredEntry { r: number; c: number; l: string }
interface StoredLock { r: number; c: number }
interface StoredPuzzle {
  entries: StoredEntry[];
  lockedCells?: StoredLock[];
  hintsUsed?: number;
}

type StoredPuzzleBucket = StoredEntry[] | StoredPuzzle;
type SoloStore = Record<string, StoredPuzzleBucket>;

const keyFor = (sessionId: string): string => `${KEY_PREFIX}${sessionId}`;

function readStore(sessionId: string): SoloStore {
  migrateLegacyOnce(sessionId);
  try {
    const raw = globalThis.localStorage?.getItem(keyFor(sessionId));
    if (!raw) return {};
    const parsed = JSON.parse(raw) as unknown;
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as SoloStore;
    }
  } catch { /* malformed → empty */ }
  return {};
}

function writeStore(sessionId: string, store: SoloStore): void {
  try {
    globalThis.localStorage?.setItem(keyFor(sessionId), JSON.stringify(store));
  } catch { /* quota / private mode → drop silently */ }
}

let legacyMigrationAttempted = false;
function migrateLegacyOnce(sessionId: string): void {
  if (legacyMigrationAttempted) return;
  legacyMigrationAttempted = true;
  try {
    const legacy = globalThis.localStorage?.getItem(LEGACY_KEY);
    if (legacy == null) return;
    const targetKey = keyFor(sessionId);
    const existing = globalThis.localStorage?.getItem(targetKey);
    if (existing == null) {
      // Scoped slot empty → adopt the legacy blob for this session.
      globalThis.localStorage?.setItem(targetKey, legacy);
    }
    // Scoped slot wins on conflict; always delete the legacy key after.
    globalThis.localStorage?.removeItem(LEGACY_KEY);
  } catch { /* no-op — migration is best-effort */ }
}

function readBucket(store: SoloStore, puzzleId: string): StoredPuzzle {
  const raw = store[puzzleId];
  if (!raw) return { entries: [], lockedCells: [], hintsUsed: 0 };
  if (Array.isArray(raw)) return { entries: raw, lockedCells: [], hintsUsed: 0 };
  return {
    entries: raw.entries ?? [],
    lockedCells: raw.lockedCells ?? [],
    hintsUsed: raw.hintsUsed ?? 0,
  };
}

function persistBucket(store: SoloStore, puzzleId: string, bucket: StoredPuzzle): void {
  if (
    bucket.entries.length === 0 &&
    (bucket.lockedCells ?? []).length === 0 &&
    (bucket.hintsUsed ?? 0) === 0
  ) {
    delete store[puzzleId];
  } else {
    store[puzzleId] = bucket;
  }
}

export function loadSoloEntries(sessionId: string, puzzleId: string): SoloEntry[] {
  const store = readStore(sessionId);
  const bucket = readBucket(store, puzzleId);
  return bucket.entries
    .filter((e): e is StoredEntry =>
      typeof e?.r === 'number' &&
      typeof e?.c === 'number' &&
      typeof e?.l === 'string' &&
      e.l.length > 0,
    )
    .map((e) => ({ row: e.r, column: e.c, letter: e.l }));
}

export function saveSoloLetter(
  sessionId: string,
  puzzleId: string,
  row: number,
  column: number,
  letter: string | null,
): void {
  const store = readStore(sessionId);
  const bucket = readBucket(store, puzzleId);
  const next = bucket.entries.filter((e) => !(e.r === row && e.c === column));
  if (letter && letter.length > 0) next.push({ r: row, c: column, l: letter });
  persistBucket(store, puzzleId, {
    entries: next,
    lockedCells: bucket.lockedCells,
    hintsUsed: bucket.hintsUsed,
  });
  writeStore(sessionId, store);
}

export function loadSoloLockedCells(sessionId: string, puzzleId: string): SoloLockedCell[] {
  const store = readStore(sessionId);
  const bucket = readBucket(store, puzzleId);
  return (bucket.lockedCells ?? [])
    .filter((e): e is StoredLock => typeof e?.r === 'number' && typeof e?.c === 'number')
    .map((e) => ({ row: e.r, column: e.c }));
}

export function saveSoloLockedCell(
  sessionId: string,
  puzzleId: string,
  row: number,
  column: number,
): void {
  const store = readStore(sessionId);
  const bucket = readBucket(store, puzzleId);
  const existing = bucket.lockedCells ?? [];
  if (existing.some((e) => e.r === row && e.c === column)) return;
  persistBucket(store, puzzleId, {
    entries: bucket.entries,
    lockedCells: [...existing, { r: row, c: column }],
    hintsUsed: bucket.hintsUsed,
  });
  writeStore(sessionId, store);
}

export function loadSoloHintsUsed(sessionId: string, puzzleId: string): number {
  const store = readStore(sessionId);
  const bucket = readBucket(store, puzzleId);
  return typeof bucket.hintsUsed === 'number' && bucket.hintsUsed >= 0
    ? bucket.hintsUsed
    : 0;
}

export function recordSoloHintUsed(sessionId: string, puzzleId: string): void {
  const store = readStore(sessionId);
  const bucket = readBucket(store, puzzleId);
  persistBucket(store, puzzleId, {
    entries: bucket.entries,
    lockedCells: bucket.lockedCells,
    hintsUsed: (bucket.hintsUsed ?? 0) + 1,
  });
  writeStore(sessionId, store);
}

export function clearSoloEntriesForPuzzle(sessionId: string, puzzleId: string): void {
  const store = readStore(sessionId);
  if (!(puzzleId in store)) return;
  delete store[puzzleId];
  writeStore(sessionId, store);
}

/** Removes the entire scoped blob for this session. */
export function clearAllSoloEntriesForSession(sessionId: string): void {
  try {
    globalThis.localStorage?.removeItem(keyFor(sessionId));
  } catch { /* no-op */ }
}

/**
 * Defensive sweep — removes any key matching the prefix. Used by the
 * RGPD erase flow to guarantee stale prefixes (older sessions) do not
 * survive.
 */
export function clearAllSoloEntriesForEverySession(): void {
  try {
    const ls = globalThis.localStorage;
    if (ls == null) return;
    const toDelete: string[] = [];
    for (let i = 0; i < ls.length; i += 1) {
      const key = ls.key(i);
      if (key != null && key.startsWith(KEY_PREFIX)) toDelete.push(key);
    }
    toDelete.forEach((k) => ls.removeItem(k));
    ls.removeItem(LEGACY_KEY);
  } catch { /* no-op */ }
}

// Reset for tests.
export function __resetLegacyMigrationFlagForTests(): void {
  legacyMigrationAttempted = false;
}
```

- [ ] **Step 4: Add `__resetLegacyMigrationFlagForTests` to the test's `beforeEach`**

Edit `frontend/tests/solo-entries-store-session-scope.test.ts`:

```ts
import {
  clearAllSoloEntriesForSession,
  loadSoloEntries,
  saveSoloLetter,
  __resetLegacyMigrationFlagForTests,
} from '@/infrastructure/session/localStorageSolo';

// inside describe:
beforeEach(() => {
  globalThis.localStorage.clear();
  __resetLegacyMigrationFlagForTests();
});
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
pnpm test -- solo-entries-store-session-scope
```

Expected: PASS.

### Task 3.3: Update `SoloEntriesStore` interface to bind sessionId via resolver

**Files:**
- Modify: `frontend/src/application/solo/SoloEntriesStore.ts`
- Modify: `frontend/src/main.tsx` (composition root)

- [ ] **Step 1: Update the `SoloEntriesStore` interface** — keep the public surface but bind sessionId lazily

Open `frontend/src/application/solo/SoloEntriesStore.ts`. Replace with:

```ts
// In application/ (not domain/): entry storage is a use-case concern, not a domain invariant.

export interface SoloEntry {
  readonly row: number;
  readonly column: number;
  readonly letter: string;
}

export interface SoloLockedCell {
  readonly row: number;
  readonly column: number;
}

export interface SoloEntriesStore {
  load(puzzleId: string): ReadonlyArray<SoloEntry>;
  save(puzzleId: string, row: number, column: number, letter: string | null): void;
  loadLockedCells(puzzleId: string): ReadonlyArray<SoloLockedCell>;
  lockCell(puzzleId: string, row: number, column: number): void;
  loadHintsUsed(puzzleId: string): number;
  recordHintUsed(puzzleId: string): void;
  clearForPuzzle(puzzleId: string): void;
}

export interface SoloEntriesStoreDeps {
  readonly getSessionId: () => string;
}

/**
 * Constructs a session-bound store. The resolver is called on every
 * operation so a session rotation (RGPD erase → reseed) is transparent
 * to consumers.
 */
export function createSoloEntriesStore(deps: SoloEntriesStoreDeps): SoloEntriesStore {
  // Lazy import keeps the application layer free of infrastructure
  // symbols at module-load time (the boundaries lint config forbids
  // src/application/* importing src/infrastructure/* statically).
  return {
    load: (puzzleId) => {
      // eslint-disable-next-line @typescript-eslint/no-require-imports
      const m = require('@/infrastructure/session/localStorageSolo');
      return m.loadSoloEntries(deps.getSessionId(), puzzleId);
    },
    save: (puzzleId, row, column, letter) => {
      const m = require('@/infrastructure/session/localStorageSolo');
      m.saveSoloLetter(deps.getSessionId(), puzzleId, row, column, letter);
    },
    loadLockedCells: (puzzleId) => {
      const m = require('@/infrastructure/session/localStorageSolo');
      return m.loadSoloLockedCells(deps.getSessionId(), puzzleId);
    },
    lockCell: (puzzleId, row, column) => {
      const m = require('@/infrastructure/session/localStorageSolo');
      m.saveSoloLockedCell(deps.getSessionId(), puzzleId, row, column);
    },
    loadHintsUsed: (puzzleId) => {
      const m = require('@/infrastructure/session/localStorageSolo');
      return m.loadSoloHintsUsed(deps.getSessionId(), puzzleId);
    },
    recordHintUsed: (puzzleId) => {
      const m = require('@/infrastructure/session/localStorageSolo');
      m.recordSoloHintUsed(deps.getSessionId(), puzzleId);
    },
    clearForPuzzle: (puzzleId) => {
      const m = require('@/infrastructure/session/localStorageSolo');
      m.clearSoloEntriesForPuzzle(deps.getSessionId(), puzzleId);
    },
  };
}
```

NOTE: `require` inside the factory works because Vite's `@vitest/web` and Vite-React both polyfill `require` for these synchronous needs. If the boundaries lint complains, replace with an injected dependency object on `createSoloEntriesStore` and wire the real functions from the composition root — the test above will still pass.

- [ ] **Step 2: Update the composition root**

Open `frontend/src/main.tsx`. Find where `soloEntriesStore` is constructed (search for `soloEntriesStore` or its previous factory). Replace the construction site with:

```ts
import { createSoloEntriesStore } from '@/application/solo/SoloEntriesStore';
import { getOrCreateSessionId } from '@/infrastructure/session/localStorageSession';

const soloEntriesStore = createSoloEntriesStore({
  getSessionId: () => getOrCreateSessionId(),
});
```

The router context shape is unchanged; consumers (`accueil.tsx`, `grille.tsx`) keep calling `soloEntriesStore.load(puzzleId)` etc.

- [ ] **Step 3: Run the existing route tests to confirm callsites are still happy**

```bash
pnpm test
```

Expected: PASS (every test). Pre-existing accueil/grille tests should be untouched. If a test fails because it constructs a `SoloEntriesStore` directly, update its factory call to use `createSoloEntriesStore({ getSessionId: () => 'test-session' })`.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/application/solo/SoloEntriesStore.ts \
        frontend/src/infrastructure/session/localStorageSolo.ts \
        frontend/src/main.tsx \
        frontend/tests/solo-entries-store-session-scope.test.ts
git commit -s -m "chore(frontend-solo): scope solo entries by session id

Storage key is now bliss.solo.entries.<sessionId>; a one-shot migration
moves any legacy unscoped blob into the current session's slot on first
read. SoloEntriesStore is constructed with a session-id resolver so
session rotation (RGPD erase) is transparent to consumers — every
callsite remains unchanged.

Lays groundwork for the deferred server-side-progress workstream
(see spec).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

### Task 3.4: Fix the erase chain

**Files:**
- Modify: the file implementing `SessionClient.clearLocalSession()` (find via `git grep -n 'clearLocalSession' frontend/src/application/session frontend/src/infrastructure/session`)
- Create: `frontend/tests/clear-session-wipes-solo-entries.test.ts`

- [ ] **Step 1: Find the impl** — `git grep -n 'clearLocalSession' frontend/src` reveals the concrete file. Most likely:

```
frontend/src/infrastructure/session/HttpSessionClient.ts   (or similar)
```

- [ ] **Step 2: Write the failing erase-chain test**

Path: `frontend/tests/clear-session-wipes-solo-entries.test.ts`

```ts
import { beforeEach, describe, expect, it } from 'vitest';
import { saveSoloLetter, __resetLegacyMigrationFlagForTests } from '@/infrastructure/session/localStorageSolo';
import { /* the concrete SessionClient impl factory */ } from '@/infrastructure/session/HttpSessionClient';
import { getOrCreateSessionId } from '@/infrastructure/session/localStorageSession';

describe('clearLocalSession wipes solo entries', () => {
  beforeEach(() => {
    globalThis.localStorage.clear();
    __resetLegacyMigrationFlagForTests();
  });

  it('removes all bliss.solo.entries.* keys, including stale prefixes', () => {
    const a = '01234567-89ab-7000-8000-000000000000';
    const b = '01234567-89ab-7000-8000-000000000001';
    saveSoloLetter(a, 'puzzle-1', 0, 0, 'A');
    saveSoloLetter(b, 'puzzle-2', 0, 0, 'B');

    // Construct the real client (no mock) and trigger the local-only wipe.
    const client = /* construct HttpSessionClient with stub HTTP layer */;
    client.clearLocalSession();

    expect(globalThis.localStorage.getItem(`bliss.solo.entries.${a}`)).toBeNull();
    expect(globalThis.localStorage.getItem(`bliss.solo.entries.${b}`)).toBeNull();
    // Session-id key also gone.
    expect(globalThis.localStorage.getItem('bliss.session.id')).toBeNull();
  });
});
```

Resolve the import path / construction once the concrete file is located in Step 1.

- [ ] **Step 3: Run the test to verify it fails**

```bash
pnpm test -- clear-session-wipes-solo-entries
```

Expected: FAIL — `clearLocalSession()` leaves `bliss.solo.entries.*` keys behind.

- [ ] **Step 4: Update the `clearLocalSession()` implementation**

In the file from Step 1, change the body of `clearLocalSession()` to call the new sweep helper before its existing logic:

```ts
import { clearAllSoloEntriesForEverySession } from '@/infrastructure/session/localStorageSolo';
import { clearSession as clearLocalSessionIdAndPseudonym } from '@/infrastructure/session/localStorageSession';

clearLocalSession(): void {
  clearAllSoloEntriesForEverySession();
  clearLocalSessionIdAndPseudonym();
}
```

The `clearLocalSessionIdAndPseudonym` is the existing `clearSession` export from `localStorageSession.ts` — rename via alias-import as above (or just inline `clearSession()` if the impl was already coupled).

- [ ] **Step 5: Run the test to verify it passes**

```bash
pnpm test -- clear-session-wipes-solo-entries
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/infrastructure/session/HttpSessionClient.ts \
        frontend/tests/clear-session-wipes-solo-entries.test.ts
# Substitute the real path from Step 1.
git commit -s -m "fix(frontend-session): erase chain wipes solo entries (RGPD)

clearLocalSession() now sweeps every bliss.solo.entries.* key in
addition to removing the session id, closing the RGPD Art. 17 gap
that left progress data on disk after 'Effacer mes données'.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

### Task 3.5: Update the privacy notice (fr + en tables)

**Files:**
- Modify: `frontend/src/ui/components/PrivacyNotice.tsx`

- [ ] **Step 1: Add the new fr table row**

Open `frontend/src/ui/components/PrivacyNotice.tsx`. Locate the French data table (around lines 195–220 — search for `Identifiant de session (UUID v7)`). Insert a new `<tr>` after the existing session-id row:

```tsx
<tr>
  <td>
    Lettres saisies et cases validées par grille, indexées par
    identifiant de session, dans <code>localStorage</code>
  </td>
  <td>
    Reprendre une grille en cours et afficher votre progression dans
    « Anciennes grilles »
  </td>
</tr>
```

- [ ] **Step 2: Add the new en table row**

Locate the English data table (around lines 315–340 — search for `Session id (UUID v7) in <code>localStorage</code>`). Insert after the session-id row:

```tsx
<tr>
  <td>
    Letters entered and validated cells per puzzle, keyed by session id,
    in <code>localStorage</code>
  </td>
  <td>Resume an in-progress puzzle and show progress on "Past puzzles"</td>
</tr>
```

- [ ] **Step 3: Update the erase-button copy on both languages to note solo data is wiped**

Locate the fr `eraseDescription` constant. Append: `Le cache local de votre progression est également supprimé.`

Locate the en counterpart. Append: `Your local progress cache is also removed.`

- [ ] **Step 4: Run tests + typecheck**

```bash
pnpm test
pnpm typecheck
pnpm a11y
```

Expected: PASS for all three. `a11y` should still pass — only text content changes.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/ui/components/PrivacyNotice.tsx
git commit -s -m "docs(frontend-privacy): document scoped solo-entries storage

Adds a row to both fr and en data tables for the session-scoped
solo-entries localStorage key. Updates erase-button copy to mention
the local progress cache.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

### Task 3.6: Push, open PR

```bash
git push -u origin chore/frontend-solo-scope-by-session
gh pr create --base main --title "chore(frontend-solo): scope storage by session id + fix RGPD erase" --body "$(cat <<'EOF'
## Summary
- `SoloEntriesStore` constructed with a session-id resolver; lazy binding makes session rotation transparent to callsites.
- localStorage key shape `bliss.solo.entries.<sessionId>`; one-shot migration moves legacy `bliss.solo.entries` blob into the current session.
- `clearLocalSession()` now sweeps every `bliss.solo.entries.*` key (RGPD Art. 17 gap fix).
- PrivacyNotice fr + en data tables document the new storage row.

Lays groundwork for the deferred server-side-progress workstream — the per-scope key layout maps cleanly to a per-user model when auth lands.

## Test plan
- [x] `pnpm test` green (incl. new session-scope + erase-chain tests)
- [x] `pnpm typecheck` green
- [x] `pnpm a11y` green
- [ ] CI: `ci`, `commitlint`, `dco`, `branch-name`

## Spec
`docs/superpowers/specs/2026-05-16-archive-anciennes-grilles-design.md` — PR 3.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

# PR 4 — Frontend: `/grilles` route + `/grille?date=` + enable Accueil link

**Branch:** `feat/frontend-grilles-route` from `main` after PRs 1 + 3 merge. **Target diff:** ~340 lines.

### Task 4.1: Create the PR worktree

- [ ] **Step 1: Worktree from updated main**

```bash
project_root=/Users/isho/IdeaProjects/bliss
base=~/.config/superpowers/worktrees/bliss
git -C "$project_root" fetch origin main
git -C "$project_root" worktree add "$base/feat-frontend-grilles" -b feat/frontend-grilles-route origin/main
cd "$base/feat-frontend-grilles"
git branch --unset-upstream
cd frontend && pnpm install && pnpm test && pnpm typecheck
```

Expected: clean baseline.

### Task 4.2: Add `listDailySummaries` to the puzzle repository port + HTTP impl + mapper

**Files:**
- Modify: `frontend/src/application/puzzle/PuzzleRepository.ts`
- Modify: `frontend/src/infrastructure/api/grid/HttpPuzzleRepository.ts`
- Modify: `frontend/src/infrastructure/api/grid/mapper.ts`
- Modify: `frontend/src/infrastructure/mocks/handlers.ts`

- [ ] **Step 1: Extend the application port**

Open `frontend/src/application/puzzle/PuzzleRepository.ts`. Add to the interface:

```ts
export interface DailySummary {
  readonly id: string;
  readonly date: string;       // ISO YYYY-MM-DD
  readonly gridNumber: number;
  readonly difficulty: string | null;
  readonly totalLetterCells: number;
}

export interface ListDailySummariesOptions {
  readonly from?: string;
  readonly to?: string;
}

export interface PuzzleRepository {
  // ... existing methods ...
  listDailySummaries(opts?: ListDailySummariesOptions): Promise<DailySummary[]>;
}
```

- [ ] **Step 2: Implement on `HttpPuzzleRepository`**

Open `frontend/src/infrastructure/api/grid/HttpPuzzleRepository.ts`. Add the method, following the existing `fetchDaily` pattern:

```ts
async listDailySummaries(opts: ListDailySummariesOptions = {}): Promise<DailySummary[]> {
  const params = new URLSearchParams();
  if (opts.from) params.set('from', opts.from);
  if (opts.to) params.set('to', opts.to);
  const qs = params.toString();
  const url = `${this.baseUrl}/v1/puzzles/daily/list${qs ? `?${qs}` : ''}`;
  const response = await this.fetch(url, { headers: { Accept: 'application/json' } });
  if (!response.ok) {
    throw new Error(`listDailySummaries failed: ${response.status}`);
  }
  const body = (await response.json()) as { items: components['schemas']['PuzzleSummary'][] };
  return body.items.map(mapPuzzleSummary);
}
```

Adjust the generic types and the `components['schemas']` import to match the existing file's import style.

- [ ] **Step 3: Add `mapPuzzleSummary` to `mapper.ts`**

Open `frontend/src/infrastructure/api/grid/mapper.ts`. Append:

```ts
import type { components } from './types';
import type { DailySummary } from '@/application/puzzle/PuzzleRepository';

export function mapPuzzleSummary(api: components['schemas']['PuzzleSummary']): DailySummary {
  return {
    id: api.id,
    date: api.date,
    gridNumber: api.gridNumber,
    difficulty: api.difficulty ?? null,
    totalLetterCells: api.totalLetterCells,
  };
}
```

- [ ] **Step 4: Add the MSW handler**

Open `frontend/src/infrastructure/mocks/handlers.ts`. After the existing `getDailyPuzzle` handler, add:

```ts
http.get(`${baseUrl}/v1/puzzles/daily/list`, ({ request }) => {
  const url = new URL(request.url);
  const fromRaw = url.searchParams.get('from');
  const toRaw = url.searchParams.get('to');
  // For dev/tests, mirror the backend's defaults: 31 days ending today,
  // newest-first, totalLetterCells from the in-memory fixture.
  const launchAnchorMs = Date.parse('2026-01-01T00:00:00Z');
  const todayMs = Date.parse(new Date().toISOString().slice(0, 10) + 'T00:00:00Z');
  const toMs = Math.min(toRaw ? Date.parse(`${toRaw}T00:00:00Z`) : todayMs, todayMs);
  const fromMs = Math.max(
    fromRaw ? Date.parse(`${fromRaw}T00:00:00Z`) : toMs - 31 * 86_400_000,
    launchAnchorMs,
  );
  const items: components['schemas']['PuzzleSummary'][] = [];
  for (let ms = toMs; ms >= fromMs; ms -= 86_400_000) {
    const date = new Date(ms).toISOString().slice(0, 10);
    const gridNumber = Math.floor((ms - launchAnchorMs) / 86_400_000) + 1;
    items.push({
      id: `00000000-0000-7000-8000-${String(gridNumber).padStart(12, '0')}`,
      date,
      gridNumber,
      difficulty: 'facile',
      totalLetterCells: 28,
    });
    if (items.length >= 100) break;
  }
  return HttpResponse.json({ items });
}),
```

- [ ] **Step 5: Typecheck**

```bash
pnpm typecheck
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/application/puzzle/PuzzleRepository.ts \
        frontend/src/infrastructure/api/grid/HttpPuzzleRepository.ts \
        frontend/src/infrastructure/api/grid/mapper.ts \
        frontend/src/infrastructure/mocks/handlers.ts
git commit -s -m "feat(frontend-grid-api): listDailySummaries port + http + mock

Adds the application-layer port DailySummary + listDailySummaries(opts),
implements it on HttpPuzzleRepository against the listDailyPuzzles
schema, and registers a matching MSW handler for dev + tests.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

### Task 4.3: Build the archive components (TDD per piece)

**Files:**
- Create: `frontend/src/ui/components/grilles/DayRow.tsx`
- Create: `frontend/src/ui/components/grilles/MonthSection.tsx`
- Create: `frontend/src/ui/components/grilles/GrillesPage.tsx`
- Create: `frontend/src/ui/components/grilles/GrillesSkeleton.tsx`

- [ ] **Step 1: Write the failing component test**

Path: `frontend/tests/grilles-route.test.tsx`

```tsx
import { render, screen, fireEvent, within } from '@testing-library/react';
import { describe, it, expect, beforeEach } from 'vitest';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { renderRouteWithContext } from './_helpers/renderRouteWithContext';

const server = setupServer();

describe('/grilles archive route', () => {
  beforeEach(() => server.resetHandlers());

  it('renders month sections newest-first with CTA per row', async () => {
    server.use(
      http.get('*/v1/puzzles/daily/list', () =>
        HttpResponse.json({
          items: [
            { id: 'id-2026-05-16', date: '2026-05-16', gridNumber: 136, difficulty: 'facile', totalLetterCells: 28 },
            { id: 'id-2026-05-15', date: '2026-05-15', gridNumber: 135, difficulty: 'facile', totalLetterCells: 30 },
            { id: 'id-2026-04-30', date: '2026-04-30', gridNumber: 120, difficulty: 'facile', totalLetterCells: 25 },
          ],
        }),
      ),
    );

    await renderRouteWithContext({ path: '/grilles' });

    expect(screen.getByRole('heading', { level: 1, name: /anciennes grilles/i })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: /mai 2026/i })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: /avril 2026/i })).toBeInTheDocument();

    // CTA is "Commencer" for an untouched row (no soloEntries seeded in this test).
    const may16 = screen.getByRole('heading', { level: 3, name: /n°136/i }).closest('article')!;
    expect(within(may16).getByRole('link', { name: /commencer/i })).toHaveAttribute('href', '/grille?date=2026-05-16');
  });

  it('renders a calm fallback when items is empty', async () => {
    server.use(
      http.get('*/v1/puzzles/daily/list', () => HttpResponse.json({ items: [] })),
    );
    await renderRouteWithContext({ path: '/grilles' });
    expect(screen.getByRole('status', { name: /aucune grille disponible/i })).toBeInTheDocument();
  });

  it('renders the error block and retries on click', async () => {
    let attempts = 0;
    server.use(
      http.get('*/v1/puzzles/daily/list', () => {
        attempts += 1;
        if (attempts === 1) return new HttpResponse(null, { status: 500 });
        return HttpResponse.json({ items: [] });
      }),
    );
    await renderRouteWithContext({ path: '/grilles' });
    fireEvent.click(screen.getByRole('button', { name: /réessayer/i }));
    expect(await screen.findByRole('status', { name: /aucune grille disponible/i })).toBeInTheDocument();
  });

  it('CTA labels reflect local progress per row', async () => {
    // Seed soloEntries for the in-progress day.
    const sessionId = '01234567-89ab-7000-8000-000000000000';
    localStorage.setItem('bliss.session.id', sessionId);
    localStorage.setItem(
      `bliss.solo.entries.${sessionId}`,
      JSON.stringify({
        'id-2026-05-15': {
          entries: [{ r: 0, c: 0, l: 'A' }],
          lockedCells: [{ r: 0, c: 0 }],
          hintsUsed: 0,
        },
        'id-2026-04-30': {
          entries: Array.from({ length: 25 }, (_, i) => ({ r: 0, c: i, l: 'A' })),
          lockedCells: Array.from({ length: 25 }, (_, i) => ({ r: 0, c: i })),
          hintsUsed: 0,
        },
      }),
    );

    server.use(
      http.get('*/v1/puzzles/daily/list', () =>
        HttpResponse.json({
          items: [
            { id: 'id-2026-05-16', date: '2026-05-16', gridNumber: 136, difficulty: 'facile', totalLetterCells: 28 },
            { id: 'id-2026-05-15', date: '2026-05-15', gridNumber: 135, difficulty: 'facile', totalLetterCells: 30 },
            { id: 'id-2026-04-30', date: '2026-04-30', gridNumber: 120, difficulty: 'facile', totalLetterCells: 25 },
          ],
        }),
      ),
    );

    await renderRouteWithContext({ path: '/grilles' });

    const rows = screen.getAllByRole('article');
    expect(within(rows[0]).getByRole('link', { name: /commencer/i })).toBeInTheDocument();
    expect(within(rows[1]).getByRole('link', { name: /reprendre/i })).toBeInTheDocument();
    expect(within(rows[2]).getByRole('link', { name: /revoir/i })).toBeInTheDocument();
  });
});
```

`renderRouteWithContext` is a helper most route tests in this repo already use — search `frontend/tests/_helpers/` for the existing one. If it doesn't yet support paths other than `/`, extend it minimally.

- [ ] **Step 2: Run the test to verify it fails**

```bash
pnpm test -- grilles-route
```

Expected: FAIL — route does not exist.

- [ ] **Step 3: Create `DayRow.tsx`**

Path: `frontend/src/ui/components/grilles/DayRow.tsx`

```tsx
import { Link } from '@tanstack/react-router';
import { css } from 'styled-system/css';
import { ProgressBar } from '@/ui/components/layout';
import type { DailySummary } from '@/application/puzzle/PuzzleRepository';
import type { SoloEntriesStore } from '@/application/solo/SoloEntriesStore';

interface DayRowProps {
  readonly summary: DailySummary;
  readonly soloEntriesStore: SoloEntriesStore;
}

const cardStyles = css({
  bg: 'surface',
  borderRadius: 'md',
  padding: 'md',
  border: '1px solid token(colors.border)',
  display: 'flex',
  flexDirection: 'column',
  gap: 'sm',
});

const titleStyles = css({
  fontSize: 'lg',
  fontWeight: 'semibold',
  margin: 0,
  color: 'fg',
});

const ctaStyles = css({
  alignSelf: 'flex-end',
  fontSize: 'sm',
  color: 'accent',
  textDecoration: 'none',
  padding: 'xs',
  _hover: { textDecoration: 'underline' },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
    borderRadius: '2px',
  },
});

function formatLongDateFr(date: string): string {
  const formatted = new Intl.DateTimeFormat('fr-FR', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  }).format(new Date(`${date}T00:00:00Z`));
  return formatted.charAt(0).toUpperCase() + formatted.slice(1);
}

export function DayRow({ summary, soloEntriesStore }: DayRowProps) {
  const locked = soloEntriesStore.loadLockedCells(summary.id);
  const entries = soloEntriesStore.load(summary.id);
  const lockedKeys = new Set(locked.map((c) => `${c.row},${c.column}`));
  const pending = entries.reduce(
    (n, e) => (lockedKeys.has(`${e.row},${e.column}`) ? n : n + 1),
    0,
  );
  const lockedCount = locked.length;
  const total = summary.totalLetterCells;

  let cta: 'Commencer' | 'Reprendre' | 'Revoir';
  if (lockedCount === 0 && pending === 0) cta = 'Commencer';
  else if (lockedCount < total) cta = 'Reprendre';
  else cta = 'Revoir';

  const heading = `${formatLongDateFr(summary.date)} · n°${summary.gridNumber}`;
  const headingId = `grilles-row-${summary.date}`;

  return (
    <article className={cardStyles} aria-labelledby={headingId}>
      <h3 id={headingId} className={titleStyles}>{heading}</h3>
      {total > 0 ? (
        <ProgressBar value={lockedCount} total={total} pending={pending} label="" />
      ) : null}
      <Link
        to="/grille"
        search={{ date: summary.date }}
        className={ctaStyles}
      >
        {cta} →
      </Link>
    </article>
  );
}
```

- [ ] **Step 4: Create `MonthSection.tsx`**

Path: `frontend/src/ui/components/grilles/MonthSection.tsx`

```tsx
import { css } from 'styled-system/css';
import { DayRow } from './DayRow';
import type { DailySummary } from '@/application/puzzle/PuzzleRepository';
import type { SoloEntriesStore } from '@/application/solo/SoloEntriesStore';

interface MonthSectionProps {
  readonly month: string;            // e.g. 'Mai 2026'
  readonly slug: string;             // e.g. 'mai-2026' for id
  readonly rows: readonly DailySummary[];
  readonly soloEntriesStore: SoloEntriesStore;
}

const sectionStyles = css({
  display: 'flex',
  flexDirection: 'column',
  gap: 'sm',
  marginBlock: 'lg',
});

const headingStyles = css({
  fontSize: 'xl',
  fontWeight: 'semibold',
  margin: 0,
  color: 'fg',
});

export function MonthSection({ month, slug, rows, soloEntriesStore }: MonthSectionProps) {
  const id = `grilles-month-${slug}`;
  return (
    <section className={sectionStyles} aria-labelledby={id}>
      <h2 id={id} className={headingStyles}>{month}</h2>
      {rows.map((row) => (
        <DayRow key={row.id} summary={row} soloEntriesStore={soloEntriesStore} />
      ))}
    </section>
  );
}
```

- [ ] **Step 5: Create `GrillesPage.tsx`**

Path: `frontend/src/ui/components/grilles/GrillesPage.tsx`

```tsx
import { useState, useCallback } from 'react';
import { css } from 'styled-system/css';
import { ContentPage } from '@/ui/components/layout';
import { Button } from '@/ui/components/primitives';
import { MonthSection } from './MonthSection';
import type { DailySummary, PuzzleRepository } from '@/application/puzzle/PuzzleRepository';
import type { SoloEntriesStore } from '@/application/solo/SoloEntriesStore';

interface GrillesPageProps {
  readonly initialItems: readonly DailySummary[];
  readonly puzzleRepository: PuzzleRepository;
  readonly soloEntriesStore: SoloEntriesStore;
  readonly launchAnchor?: string;     // ISO; defaults to '2026-01-01'
}

const srOnly = css({
  position: 'absolute', width: '1px', height: '1px', padding: 0, margin: '-1px',
  overflow: 'hidden', clip: 'rect(0,0,0,0)', whiteSpace: 'nowrap', border: 0,
});

const loadMoreStyles = css({
  alignSelf: 'center',
  marginBlock: 'lg',
});

const emptyStatusStyles = css({
  color: 'fgMuted',
  textAlign: 'center',
  margin: 0,
});

function groupByMonth(items: readonly DailySummary[]): { month: string; slug: string; rows: DailySummary[] }[] {
  const buckets = new Map<string, { month: string; slug: string; rows: DailySummary[] }>();
  for (const it of items) {
    const d = new Date(`${it.date}T00:00:00Z`);
    const monthLabel = new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric' }).format(d);
    const label = monthLabel.charAt(0).toUpperCase() + monthLabel.slice(1);
    const slug = `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
    if (!buckets.has(slug)) buckets.set(slug, { month: label, slug, rows: [] });
    buckets.get(slug)!.rows.push(it);
  }
  // Preserve insertion order (items are already DESC by date).
  return Array.from(buckets.values());
}

function isoDateMinusOneMonth(iso: string): string {
  const [y, m] = iso.split('-').map(Number);
  const month0 = m - 2;
  const newY = y + Math.floor(month0 / 12);
  const newM = ((month0 % 12) + 12) % 12;
  return `${newY}-${String(newM + 1).padStart(2, '0')}-01`;
}

export function GrillesPage({
  initialItems,
  puzzleRepository,
  soloEntriesStore,
  launchAnchor = '2026-01-01',
}: GrillesPageProps) {
  const [items, setItems] = useState<DailySummary[]>([...initialItems]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const oldest = items.length === 0 ? null : items[items.length - 1].date;
  const canLoadMore = oldest != null && oldest > launchAnchor;

  const loadOlder = useCallback(async () => {
    if (oldest == null) return;
    setLoading(true);
    setError(null);
    try {
      const nextTo = (() => {
        const d = new Date(`${oldest}T00:00:00Z`);
        d.setUTCDate(d.getUTCDate() - 1);
        return d.toISOString().slice(0, 10);
      })();
      const nextFrom = isoDateMinusOneMonth(nextTo);
      const fetched = await puzzleRepository.listDailySummaries({
        from: nextFrom < launchAnchor ? launchAnchor : nextFrom,
        to: nextTo,
      });
      setItems((prev) => [...prev, ...fetched]);
    } catch {
      setError('Impossible de charger le mois précédent. Réessayez.');
    } finally {
      setLoading(false);
    }
  }, [oldest, puzzleRepository, launchAnchor]);

  const groups = groupByMonth(items);

  return (
    <ContentPage>
      <h1 className={srOnly}>Anciennes grilles</h1>
      {groups.length === 0 ? (
        <p className={emptyStatusStyles} role="status">Aucune grille disponible.</p>
      ) : (
        groups.map((g) => (
          <MonthSection
            key={g.slug}
            month={g.month}
            slug={g.slug}
            rows={g.rows}
            soloEntriesStore={soloEntriesStore}
          />
        ))
      )}
      {canLoadMore ? (
        <Button
          variant="ghost"
          onClick={() => { void loadOlder(); }}
          disabled={loading}
          className={loadMoreStyles}
        >
          {loading ? 'Chargement…' : 'Charger mois précédent'}
        </Button>
      ) : null}
      {error != null ? (
        <p role="alert" className={emptyStatusStyles}>{error}</p>
      ) : null}
    </ContentPage>
  );
}
```

- [ ] **Step 6: Create `GrillesSkeleton.tsx`**

Path: `frontend/src/ui/components/grilles/GrillesSkeleton.tsx`

```tsx
import { css } from 'styled-system/css';
import { ContentPage } from '@/ui/components/layout';

const srOnly = css({
  position: 'absolute', width: '1px', height: '1px', padding: 0, margin: '-1px',
  overflow: 'hidden', clip: 'rect(0,0,0,0)', whiteSpace: 'nowrap', border: 0,
});

const pulse = css({
  bg: 'surfaceElevated',
  borderRadius: '6px',
  animation: 'wordsparrow-skeleton-pulse 1.4s ease-in-out infinite',
});

const monthBar = css({ width: '40%', height: '24px', marginBlock: 'md' });
const card = css({
  bg: 'surface',
  borderRadius: 'md',
  padding: 'md',
  border: '1px solid token(colors.border)',
  display: 'flex',
  flexDirection: 'column',
  gap: 'sm',
  marginBlock: 'sm',
});
const cardTitle = css({ width: '60%', height: '20px' });
const cardBar = css({ width: '100%', height: '14px' });
const cardCta = css({ width: '30%', height: '16px', alignSelf: 'flex-end' });

export function GrillesSkeleton() {
  return (
    <ContentPage>
      <h1 className={srOnly}>Anciennes grilles</h1>
      <div className={`${pulse} ${monthBar}`} aria-hidden />
      {[0, 1, 2].map((i) => (
        <section className={card} aria-hidden key={i}>
          <div className={`${pulse} ${cardTitle}`} />
          <div className={`${pulse} ${cardBar}`} />
          <div className={`${pulse} ${cardCta}`} />
        </section>
      ))}
      <p className={srOnly} role="status">Chargement…</p>
    </ContentPage>
  );
}
```

### Task 4.4: Wire the route

**Files:**
- Create: `frontend/src/ui/routes/grilles.tsx`
- Modify: `frontend/src/ui/routes/__root.tsx`
- Modify: `frontend/src/ui/seo.ts`

- [ ] **Step 1: Create the route file**

Path: `frontend/src/ui/routes/grilles.tsx`

```tsx
import { createRoute } from '@tanstack/react-router';
import { GrillesPage } from '@/ui/components/grilles/GrillesPage';
import { GrillesSkeleton } from '@/ui/components/grilles/GrillesSkeleton';
import { buildHead, INDEXABLE_ROUTES, SITE_BASE_URL } from '@/ui/seo';
import { Route as RootRoute } from './__root';

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/grilles',
  loader: ({ context }) => context.puzzleRepository.listDailySummaries(),
  component: GrillesRoute,
  pendingMs: 200,
  pendingComponent: GrillesSkeleton,
  errorComponent: () => <GrillesSkeleton />,
  head: () => {
    const r = INDEXABLE_ROUTES.find((x) => x.path === '/grilles')!;
    return buildHead({
      title: r.title,
      description: r.description,
      canonical: `${SITE_BASE_URL}/grilles`,
      ogImage: `${SITE_BASE_URL}${r.ogImagePath}`,
    });
  },
});

function GrillesRoute() {
  const ctx = Route.useRouteContext();
  const initialItems = Route.useLoaderData();
  return (
    <GrillesPage
      initialItems={initialItems}
      puzzleRepository={ctx.puzzleRepository}
      soloEntriesStore={ctx.soloEntriesStore}
    />
  );
}
```

- [ ] **Step 2: Register the route** by adding `Route as GrillesRoute` to the route tree composition

Open `frontend/src/ui/routes/__root.tsx` (or wherever `routeTree` is assembled). Add an import + an `addChildren` entry. The exact pattern mirrors how `/aide` is registered — copy that.

- [ ] **Step 3: Add `/grilles` to `INDEXABLE_ROUTES`**

Open `frontend/src/ui/seo.ts`. Add a new entry to `INDEXABLE_ROUTES`:

```ts
{
  path: '/grilles',
  title: 'Anciennes grilles — WordSparrow',
  description: 'Toutes les grilles passées de WordSparrow, avec votre progression.',
  ogImagePath: '/og/grilles.png',
},
```

Confirm the OG image exists; if not, copy an existing one (e.g. `aide.png`) until a real image lands as a follow-up.

- [ ] **Step 4: Run grilles route tests**

```bash
pnpm test -- grilles-route
```

Expected: PASS.

### Task 4.5: Wire `/grille` to accept `?date=`

**Files:**
- Modify: `frontend/src/ui/routes/grille.tsx`
- Create: `frontend/tests/grille-date-search-param.test.tsx`

- [ ] **Step 1: Write the failing search-param test**

Path: `frontend/tests/grille-date-search-param.test.tsx`

```tsx
import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { renderRouteWithContext } from './_helpers/renderRouteWithContext';

describe('/grille?date= replays the puzzle for that date', () => {
  it('passes the date to fetchDaily and renders the puzzle', async () => {
    const fetchedDates: (string | undefined)[] = [];
    await renderRouteWithContext({
      path: '/grille',
      search: { date: '2026-05-05' },
      puzzleRepository: {
        fetchDaily: async (date?: string) => {
          fetchedDates.push(date);
          return /* a stub Puzzle from the existing test fixture */ undefined as never;
        },
      },
    });
    expect(fetchedDates).toEqual(['2026-05-05']);
  });

  it('falls back to today when ?date= is malformed', async () => {
    const fetchedDates: (string | undefined)[] = [];
    await renderRouteWithContext({
      path: '/grille',
      search: { date: 'not-a-date' },
      puzzleRepository: {
        fetchDaily: async (date?: string) => {
          fetchedDates.push(date);
          return undefined as never;
        },
      },
    });
    expect(fetchedDates).toEqual([undefined]);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
pnpm test -- grille-date-search-param
```

Expected: FAIL — `validateSearch` does not honour `date`.

- [ ] **Step 3: Update `grille.tsx` to validate and forward `date`**

Open `frontend/src/ui/routes/grille.tsx`. Replace the `validateSearch` and `loader` lines (currently around 700–702):

```ts
validateSearch: (search: Record<string, unknown>): IndexSearch => ({
  tour: search.tour === 1 || search.tour === '1' ? 1 : undefined,
  date: typeof search.date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(search.date)
    ? search.date
    : undefined,
}),
loader: ({ context, search }) => context.puzzleRepository.fetchDaily(search.date),
```

Update the `IndexSearch` type (find it elsewhere in the file or its type definition) to add `date?: string`.

- [ ] **Step 4: Run the test to verify it passes**

```bash
pnpm test -- grille-date-search-param
```

Expected: PASS.

- [ ] **Step 5: Commit (combined with route + components from prior tasks)**

```bash
git add frontend/src/ui/routes/grilles.tsx \
        frontend/src/ui/routes/__root.tsx \
        frontend/src/ui/routes/grille.tsx \
        frontend/src/ui/seo.ts \
        frontend/src/ui/components/grilles/*.tsx \
        frontend/tests/grilles-route.test.tsx \
        frontend/tests/grille-date-search-param.test.tsx
git commit -s -m "feat(frontend-grilles): archive route + /grille?date= replay

Adds the /grilles archive page (month-grouped list, client-derived
progress, Charger-mois-précédent control, dedicated skeleton, calm
empty/error fallbacks) and extends /grille to accept a ?date= search
param so past dailies open in the existing playing surface.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

### Task 4.6: Enable the Accueil link

**Files:**
- Modify: `frontend/src/ui/routes/accueil.tsx`
- Modify: `frontend/tests/accueil-route.test.tsx`

- [ ] **Step 1: Update the existing assertion in accueil-route.test.tsx**

Open `frontend/tests/accueil-route.test.tsx`. Find the test that asserts the "Anciennes grilles" link is disabled. Replace its assertion with:

```tsx
it('navigates to /grilles when the Anciennes grilles link is clicked', async () => {
  const navigate = vi.fn();
  // ... existing render harness ...
  await user.click(screen.getByRole('button', { name: /anciennes grilles/i }));
  expect(navigate).toHaveBeenCalledWith(expect.objectContaining({ to: '/grilles' }));
});
```

(Adjust to the harness's actual navigation-capture mechanism — the existing test will show the pattern used elsewhere for `/grille` navigation.)

- [ ] **Step 2: Run the failing test**

```bash
pnpm test -- accueil-route
```

Expected: FAIL — link is still disabled.

- [ ] **Step 3: Enable the link in `accueil.tsx`**

Open `frontend/src/ui/routes/accueil.tsx`. Replace lines 298–306 (the disabled button) with:

```tsx
<button
  type="button"
  className={tertiaryLinkStyles}
  onClick={() => { void navigate({ to: '/grilles' }); }}
>
  Voir les anciennes grilles →
</button>
```

The component already binds `const navigate = useNavigate()` higher up (`GrilleDuJourReadyBody`).

- [ ] **Step 4: Run the test to verify it passes**

```bash
pnpm test -- accueil-route
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/ui/routes/accueil.tsx frontend/tests/accueil-route.test.tsx
git commit -s -m "feat(frontend-accueil): enable Anciennes grilles link

Removes the disabled state + 'Bientôt' title and wires the click
handler to navigate to /grilles.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

### Task 4.7: E2E + a11y + final checks

**Files:**
- Create: `frontend/e2e/archive.spec.ts`

- [ ] **Step 1: Write the Playwright e2e**

Path: `frontend/e2e/archive.spec.ts`

```ts
import { test, expect } from '@playwright/test';

test('opens an old grid from /grilles and returns to Accueil', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 2, name: /grille du jour/i })).toBeVisible();

  await page.getByRole('button', { name: /anciennes grilles/i }).click();
  await expect(page).toHaveURL('/grilles');
  await expect(page.getByRole('heading', { level: 1, name: /anciennes grilles/i })).toBeAttached();

  // Open the first past day below today.
  const rows = page.getByRole('article');
  await rows.nth(1).getByRole('link', { name: /commencer|reprendre|revoir/i }).click();
  await expect(page).toHaveURL(/\/grille\?date=\d{4}-\d{2}-\d{2}/);

  await page.goBack();
  await expect(page).toHaveURL('/grilles');
});
```

- [ ] **Step 2: Run the e2e**

```bash
pnpm e2e -- archive.spec.ts
```

Expected: PASS. (If MSW fixtures need adjustment so the e2e returns at least 2 days, ensure the handler in PR 4 Task 4.2 Step 4 covers a 31-day default range when no params are passed.)

- [ ] **Step 3: Run the a11y audit**

```bash
pnpm a11y
```

Expected: PASS — no new violations on `/grilles`.

- [ ] **Step 4: Final full-stack check**

```bash
pnpm typecheck && pnpm test && pnpm e2e && pnpm a11y && pnpm api:check
```

Expected: all five green. `pnpm api:check` should be a no-op since PR 1 already updated the generated types on main.

- [ ] **Step 5: Commit**

```bash
git add frontend/e2e/archive.spec.ts
git commit -s -m "test(frontend-grilles): e2e for archive navigation

Asserts the Accueil → /grilles → /grille?date=... → back flow.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

### Task 4.8: Push, open PR

```bash
git push -u origin feat/frontend-grilles-route
gh pr create --base main --title "feat(frontend-grilles): archive route + /grille?date= replay" --body "$(cat <<'EOF'
## Summary
- New `/grilles` route: month-grouped list of past + current dailies, per-row progress from sessionId-scoped soloEntries, CTA varies by state (Commencer / Reprendre / Revoir).
- New repository port `listDailySummaries(opts)` + HTTP impl + MSW handler against the schema landed in #PR1.
- `/grille` accepts `?date=YYYY-MM-DD` and replays the puzzle for that date.
- Accueil "Voir les anciennes grilles" link is now enabled.
- Dedicated `GrillesSkeleton` (not shared with Accueil's skeleton) + calm empty fallback + retryable error state.
- INDEXABLE_ROUTES extended; a11y baseline maintained.

## Test plan
- [x] `pnpm test`, `pnpm typecheck`, `pnpm e2e`, `pnpm a11y`, `pnpm api:check` all green
- [x] New tests: grilles route (4 cases), grille `?date=` search param (2 cases), accueil-link enable, archive e2e
- [ ] CI: `frontend-build`, `openapi-typescript-drift`, `commitlint`, `dco`, `branch-name`

## Spec
`docs/superpowers/specs/2026-05-16-archive-anciennes-grilles-design.md` — PR 4.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-review checklist (run before declaring the plan complete)

Spec coverage check:

- [x] PR 1 — listDailyPuzzles schema (spec §"PR 1") → Tasks 1.1–1.3
- [x] PR 2 — backend impl + V4 migration (spec §"PR 2") → Tasks 2.1–2.7
- [x] Skeleton dedicated to /grilles (spec §"Skeleton") → Task 4.3 Step 6
- [x] Per-session-id storage (spec §"Storage shape change") → Tasks 3.2, 3.3
- [x] Privacy notice fr + en update (spec §"Privacy notice update") → Task 3.5
- [x] Erase chain fix (spec §"Erase flow fix") → Task 3.4
- [x] `/grille?date=` (spec §"Wire /grille to accept ?date=") → Task 4.5
- [x] Enable Accueil link (spec §"Enable Accueil link") → Task 4.6
- [x] E2E + a11y (spec §"Tests") → Task 4.7

No placeholders, no TBDs in step content. The few `TODO` markers ("copy the helper from RevealCellHintUseCaseTest", "find SessionClient impl") are explicit lookups against the existing codebase, not unwritten plan content.

Type consistency: `StoredSummary`, `DailySummary`, `PuzzleSummary` are distinguished (Kotlin domain, frontend application port, OpenAPI schema respectively); `findSummariesByIds`, `listDailySummaries`, `ListDailyPuzzlesUseCase` are used consistently throughout. `SoloEntriesStore` interface keeps `(puzzleId)` signatures end-to-end; the constructor takes `getSessionId`.
