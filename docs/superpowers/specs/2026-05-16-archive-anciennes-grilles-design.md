# Voir les anciennes grilles

**Status**: design approved 2026-05-16, awaiting implementation plan.

## Why

The Accueil page already advertises an "Anciennes grilles" entry-point that has shipped disabled (`title="Bientôt"`, `accueil.tsx:298-305`) since the daily-puzzle worker (ADR-0042) started populating one row per UTC date. The database now holds ~135 past dailies with ~1 added daily; players have no surface for revisiting them or for resuming partial games beyond today's. The archive lights up that existing entry-point with a dedicated route.

## Scope

In scope:

- A new `/grilles` route listing every daily since the launch anchor (2026-01-01), grouped by month, newest-first, including today's.
- Per-row progress derived from the existing solo-entries store; CTA copy reflects state (Commencer / Reprendre / Revoir).
- A new thin "list summaries" endpoint on `grid-api` so the page loads in a single round-trip per month.
- Existing `/grille` route accepts `?date=YYYY-MM-DD` so a past daily replays in-place.
- A foundational refactor: solo-entries `localStorage` is scoped by session id (groundwork for the server-side-progress workstream that comes next; see *Future work*).
- Privacy notice (fr + en) is updated to document solo-entries storage, and the RGPD Art. 17 erase flow is fixed to wipe it.

Out of scope:

- Server-side persistence of solo progress as source of truth. Acknowledged as the right direction; deliberately deferred to its own ADR + workstream so the archive is not stapled to an infra rewrite. Until that lands, progress remains a local-only artefact and is invisible across devices.
- Search, filter, or sort beyond the default reverse-chronological listing.
- Calendar heatmap or any non-list visualisation (rejected during brainstorm).
- Multiplayer past games. "Mes parties" already covers that surface; this is solo only.
- Read-only / replay UI distinct from `/grille`. Completed grids open in `/grille?date=` fully filled and locked; "Revoir" is just a label, not a separate mode.

## Architecture overview

```
┌────────────────────┐   GET /v1/puzzles/daily/list?from&to        ┌──────────────┐
│ frontend           │ ─────────────────────────────────────────► │ grid-api     │
│  /grilles route    │                                             │  Ktor handler │
│  ProgressBar reuse │ ◄───────────────────────────────────────── │              │
│  SoloEntriesStore  │   [PuzzleSummary] — sorted DESC by date    └──────┬───────┘
└────────┬───────────┘                                                   │
         │ load(puzzleId) → entries + lockedCells (per-session local)    │
         │                                                               ▼
         │                                                       ┌──────────────┐
         │                                                       │ Postgres     │
         │                                                       │  puzzles     │
         │                                                       │   + new col  │
         │                                                       └──────────────┘
         │
         ▼
   localStorage `bliss.solo.entries.<sessionId>`  (one key per session)
```

The list endpoint returns *thin* summaries — id, date, gridNumber, difficulty, totalLetterCells. The frontend zips each summary with locally-known progress to render the row. The server has no knowledge of who has played what.

## PR rollout

| PR | Branch type/scope | Diff | Depends on |
|---|---|---|---|
| 1 | `feat(grid-api-schema)` — `listDailyPuzzles` endpoint | ~80 | — |
| 2 | `feat(grid-api)` — handler, use case, `total_letter_cells` migration + worker write | ~260 | PR 1 |
| 3 | `chore(frontend-solo)` — scope `SoloEntriesStore` by sessionId, legacy migration, fix erase chain, privacy table row | ~200 | — |
| 4 | `feat(frontend-grilles)` — `/grilles` route, `/grille?date=`, enable Accueil link | ~340 | PRs 1 + 3 |

PRs 1 and 3 are independent and can land in parallel. PR 2 follows PR 1. PR 4 follows PRs 2 and 3 — and can dev against PR 1's MSW handler while PR 2 deploys.

Each PR stays under the 400-line cap (ADR-0001 §4) excluding generated types and blank lines.

## PR 1 — schema (`grid/api/openapi.yaml`)

New path under the existing `puzzles` tag:

```yaml
/v1/puzzles/daily/list:
  get:
    operationId: listDailyPuzzles
    summary: List past + current daily puzzles.
    description: |
      Returns thin summaries of dailies in the requested range, newest
      first. Always excludes future dates; clamps `from` to the launch
      anchor (2026-01-01 UTC) and `to` to today UTC. Per-user progress is
      NOT included — clients derive it client-side from the local
      solo-entries store keyed by `id`.
    parameters:
      - in: query
        name: from
        required: false
        description: |
          ISO-8601 calendar date inclusive. Defaults to 31 days before `to`
          when omitted. Values earlier than the launch anchor are clamped.
        schema: { type: string, format: date }
      - in: query
        name: to
        required: false
        description: |
          ISO-8601 calendar date inclusive. Defaults to today UTC.
          Future values are clamped to today.
        schema: { type: string, format: date }
    responses:
      '200':
        description: List resolved. Items are sorted DESC by `date`.
        content:
          application/json:
            schema:
              type: object
              required: [items]
              properties:
                items:
                  type: array
                  maxItems: 100
                  items: { $ref: '#/components/schemas/PuzzleSummary' }
      '400':
        description: |
          `from` or `to` is not a valid ISO date. RFC 7807.
          `type` = `https://bliss.example/errors/invalid-puzzle-date`.
        content:
          application/problem+json:
            schema: { $ref: '#/components/schemas/Problem' }

# components/schemas
PuzzleSummary:
  type: object
  required: [id, date, gridNumber, totalLetterCells]
  properties:
    id:                { type: string, format: uuid }      # UUID v7, same as getDailyPuzzle
    date:              { type: string, format: date }
    gridNumber:        { type: integer, minimum: 1 }
    difficulty:        { type: string, nullable: true }
    totalLetterCells:  { type: integer, minimum: 1 }
```

Notes:

- No cursor pagination. The natural unit is a calendar month; the frontend issues one range request per month.
- `from > to` after clamping → 200 with empty `items`, not 400. URL-tinkerers see an empty list, not a fault.
- Defensive cap at 100 items so a misbehaving client cannot ask for the full archive in one shot.
- The endpoint ignores `X-Session-Id`. List output is independent of who is asking.
- CI gate: `openapi-lint`. CHANGELOG entry under `grid/api/`.

## PR 2 — backend (`grid/`)

### Migration

```sql
-- V<next>__add_total_letter_cells.sql
ALTER TABLE puzzles
  ADD COLUMN total_letter_cells INTEGER;

UPDATE puzzles
SET total_letter_cells = (
  SELECT count(*)
  FROM jsonb_array_elements(puzzle_json -> 'cells') c
  WHERE c ->> 'kind' = 'letter'
)
WHERE total_letter_cells IS NULL;
```

Expand-and-contract: column is nullable to keep the migration reversible. The daily-puzzle worker is updated in this PR to populate the column on every new insert; the backfill covers existing rows in one statement. No new index — `puzzles(puzzle_date)` already covers the range scan.

### Use case (`grid/application/`)

`ListDailyPuzzlesUseCase(from: LocalDate?, to: LocalDate?, today: LocalDate)`:

1. Clamp `to` to `min(to ?? today, today)`; clamp `from` to `max(from ?? to.minusDays(31), launchAnchor)`.
2. If `from > to`, short-circuit with `Success(emptyList())`.
3. Delegate to `PuzzleRepository.findSummariesInRange(from, to, limit = 100)`.

### Repository (`grid/infrastructure/`)

```kotlin
fun findSummariesInRange(from: LocalDate, to: LocalDate, limit: Int): List<PuzzleSummary>
```

Single SELECT:

```sql
SELECT id, puzzle_date, grid_number, difficulty, total_letter_cells
FROM puzzles
WHERE puzzle_date BETWEEN ? AND ?
ORDER BY puzzle_date DESC
LIMIT ?;
```

Rows with `total_letter_cells IS NULL` are filtered out at the application layer (should never happen post-backfill; treated as defensive).

### HTTP route

`GET /v1/puzzles/daily/list` parses the two date query parameters (RFC 7807 `invalid-puzzle-date` on parse failure), invokes the use case, returns `{ items: [...] }`.

### Tests

- Use-case (in-memory repo, table-driven): default range, explicit range, `from > to`, `from < launch anchor` (clamped), `to > today` (clamped), empty range.
- Property-based: arbitrary date pairs always yield `from <= to <= today` after clamping.
- HTTP (Ktor `testApplication` against a Flyway-migrated test DB, no mocks per CLAUDE.md): 200 ordering, 200 default range, 400 on garbage date, 400 problem-detail shape.
- Worker write path: a newly inserted puzzle has `total_letter_cells` set.
- Konsist: no JDBC imports in `domain/` or `application/`.

## PR 3 — frontend solo refactor (`frontend/`)

The goals: (a) prepare for the future server-side-progress workstream by giving every solo-entry blob a session-scoped key, (b) fix the latent RGPD erase gap that today leaves solo data on disk after "Effacer mes données", (c) document the data in the privacy notice. No new UI ships in this PR.

### Storage shape change

| Before | After |
|---|---|
| `bliss.solo.entries` → `{ [puzzleId]: { entries, lockedCells } }` | `bliss.solo.entries.<sessionId>` → `{ [puzzleId]: { entries, lockedCells } }` (one key per session) |

### `SoloEntriesStore` API

Public methods (`load`, `loadLockedCells`, `save`, …) keep their `(puzzleId, …)` signatures. The session id is bound at construction via a resolver, so session rotation (erase → reseed) is transparent to callsites:

```ts
new SoloEntriesStore({ getSessionId: () => sessionClient.getSessionId() })
```

No callsite in `accueil.tsx` or `grille.tsx` changes.

### One-shot legacy migration

Runs synchronously in the composition root (`main.tsx`) at store construction, before any route mounts:

1. Read legacy `bliss.solo.entries`. If absent → done.
2. Resolve current sessionId.
3. If `bliss.solo.entries.<sessionId>` is empty/missing, write the legacy blob there. Existing scoped data wins on conflict (newer key beats older blob).
4. Remove the legacy key.

Idempotent: re-runs are no-ops. The legacy key is treated as belonging to the current device's session — only the current user could have written it.

### Erase flow fix

Today `SessionClient.clearLocalSession()` removes only the session-id key (`localStorageSession.ts:65`). Extend it to:

1. Read the current sessionId.
2. `removeItem('bliss.solo.entries.' + sessionId)`.
3. Defensive sweep: remove any other `bliss.solo.entries.*` key (covers stale prefixes from previous sessions the erase missed).
4. Remove the session-id key itself (existing behaviour).

The PrivacyNotice erase handler already calls `clearLocalSession()`; no change there.

### Privacy notice update (`PrivacyNotice.tsx`)

Add a row to both fr and en data tables:

| Donnée | Stockage | Finalité |
|---|---|---|
| Lettres saisies et cases validées par grille | `localStorage`, indexées par identifiant de session | Reprendre une grille en cours et afficher la progression dans *Anciennes grilles* |

English mirror: "Letters entered and validated cells per puzzle / `localStorage`, keyed by session id / Resume an in-progress puzzle and show progress on *Past puzzles*."

Both tables should also note: "Effacer mes données supprime aussi cette donnée locale." / "Erase removes this local data as well."

### Tests

- Property: arbitrary sessionId rotation through the resolver → previous-session data invisible, new key independent.
- Migration: legacy present → moved to scoped key; legacy absent → no-op; both present → scoped wins, legacy purged.
- Erase: post-`clearLocalSession()`, no `bliss.solo.*` and no session-id key survive in `localStorage`.
- Existing Accueil/grille route tests remain green.
- A11y / type-check / boundaries lint unchanged.

## PR 4 — frontend archive route (`frontend/`)

### Route

```ts
// frontend/src/ui/routes/grilles.tsx
export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/grilles',
  loader: ({ context }) =>
    context.puzzleRepository.listDailySummaries({ /* defaults: last 31d ending today */ }),
  component: GrillesPage,
  pendingMs: 200,
  pendingComponent: GrillesSkeleton,
  errorComponent: GrillesError,
  head: () => buildHead({ /* see SEO below */ }),
});
```

### Repository extension

```ts
// application/puzzle/PuzzleRepository.ts
listDailySummaries(opts?: { from?: string; to?: string }): Promise<DailySummary[]>;
```

Implemented in `HttpPuzzleRepository` via the regenerated client. `DailySummary` mirrors `PuzzleSummary` from the schema; mapped in `infrastructure/api/grid/mapper.ts`.

### Page layout

```
ContentPage
├── h1 sr-only "Anciennes grilles"
├── section aria-labelledby="grilles-mai-2026"
│   ├── h2#grilles-mai-2026 "Mai 2026"
│   ├── article (per day) — reuses Accueil card tokens
│   │   ├── h3   "Mardi 5 mai · n°125"
│   │   ├── ProgressBar (locked, total, pending) — reused component
│   │   └── Button → /grille?date=2026-05-05    [Commencer | Reprendre | Revoir]
│   └── …
├── section "Avril 2026"
│   ├── h2 + [Charger avril 2026]
│   └── (rows appended on click)
└── (when more available) [Charger mois précédent]
```

Sections are server-driven by range: the loader fetches the default last-31-days range; "Charger" buttons issue another range request bounded to the next older month and append the resulting bucket. State lives in `useState<MonthBucket[]>` seeded by the loader.

Per-row CTA copy:

| Local state | CTA |
|---|---|
| `lockedCount == 0 && pending == 0` | Commencer |
| `lockedCount < totalLetterCells`   | Reprendre |
| `lockedCount == totalLetterCells`  | Revoir    |

### Wire `/grille` to accept `?date=`

```ts
// routes/grille.tsx (existing route)
validateSearch: (s): IndexSearch => ({
  tour: s.tour === 1 || s.tour === '1' ? 1 : undefined,
  date: typeof s.date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(s.date) ? s.date : undefined,
}),
loader: ({ context, search }) => context.puzzleRepository.fetchDaily(search.date),
```

`fetchDaily(date?)` already accepts an optional date — no application-layer change. The grille component already renders an arbitrary `Puzzle`; nothing else needs touching for replay.

### Enable Accueil link (`accueil.tsx`)

Remove the `disabled` and `title="Bientôt"`; wire the click handler:

```tsx
<button
  type="button"
  className={tertiaryLinkStyles}
  onClick={() => { void navigate({ to: '/grilles' }); }}
>
  Voir les anciennes grilles →
</button>
```

Update `INDEXABLE_ROUTES` so `/grilles` gets a title, description, OG image, and sitemap entry (mirroring `aide` / `confidentialite`).

### Skeleton (prerender + pendingComponent)

A `GrillesSkeleton` component dedicated to this route — not shared with `AccueilSkeleton`. Same pulse tokens, different shape:

```
- pulse bar (month-header placeholder)
- 3× pulse cards (title + bar + button shapes)
- pulse "Charger" footer
- <p role="status" className={srOnly}>Chargement…</p>   (prerender sentinel)
```

The prerender script's existing `role="status"` wait mechanism works without changes.

### Loading / empty / error states

| State | Body |
|---|---|
| `loading` | `GrillesSkeleton` (per-route) |
| `ok` with rows | Month-grouped list as above |
| `ok` with zero rows | `role="status"` text "Aucune grille disponible." Calm, not an alert |
| `error` | Inline retry block mirroring `GrilleDuJourErrorBody`. Retries only the archive fetch, no router invalidation |
| `unavailable` (server returns row but `totalLetterCells == 0` — defensive) | Row falls back to CTA-only, no progress bar; siblings unaffected |

### Edge cases

- Today's row absent because the worker hasn't run yet → server omits it; archive simply does not show today until the worker lands. Accueil already handles this for the same date; behaviour stays consistent.
- Past dates with missing rows (worker had a persistent failure) → omitted. The list may be shorter than the range spans; no placeholder gaps in the UI.
- User on a fresh device or post-erase: progress is per session id, so every row reads as Commencer. Correct and matches RGPD posture; cross-device continuity is explicitly out of scope until the server-side-progress workstream.

### SEO & a11y

- `/grilles` added to `INDEXABLE_ROUTES`. New OG image follows the existing route convention. No JSON-LD beyond the inherited site-level breadcrumb.
- Each month is a real `<section aria-labelledby>` with an `<h2>`; each row is an `<article>` with `<h3>`. AT users hear a proper outline.
- "Charger mois précédent" is a real `<button>` (not a link). After async append, focus moves to the first newly-appended row's CTA — infinite-scroll best practice.
- Progress bar colour tokens already pass AA contrast (ADR-0034 baseline maintained).

### Tests

- `grilles-route.test.tsx` (Vitest + MSW):
  - Renders month grouping for a 31-day window.
  - CTA label per state (3 cases).
  - Clicking "Charger" fetches the next month range and appends a new section.
  - Zero-row response renders the calm fallback.
  - Network failure renders the retry block; retry re-fetches.
- `route-grille-date-param.test.tsx`: `/grille?date=2026-05-05` loads the puzzle for that date; bad `?date=` falls back to today; `?date=` is preserved across `pendingMs` flashes.
- `accueil-route.test.tsx` (existing): the disabled "Anciennes grilles" assertion is replaced with "navigates to `/grilles`".
- `frontend/e2e/archive.spec.ts`: open `/`, click "Voir les anciennes grilles", land on `/grilles`, open a past day, return to Accueil.
- `pnpm a11y` on the new route — must pass.

## Privacy & RGPD

Already detailed under PR 3. Summary of net changes:

- PrivacyNotice tables (fr + en) explicitly list solo-entries `localStorage` data, indexed by session id.
- `clearLocalSession()` now wipes all `bliss.solo.entries.*` keys in addition to the session-id key. RGPD Art. 17 erase becomes complete for solo state. The wire path (DELETE `/v1/sessions/{sessionId}`) is unchanged — server has no solo-entries data to wipe in this workstream.
- A flagged separate follow-up: `bliss.tour.seen` has the same erase gap. Not fixed here to keep scope tight; tracked as its own ticket.

## Observability

- Backend: structured log on each list request — `from`, `to`, `itemsReturned`, `latencyMs`. No PII.
- Frontend: existing OTel auto-instrumentation covers the new fetch. No new spans.

## Out-of-scope / future

- **Server-side solo progress as source of truth.** The right next workstream after this archive ships. Will require an ADR (new persistence surface, hot-path coupling, conflict policy, erase wire contract). The session-id-scoped local-key shape adopted in PR 3 maps cleanly to a per-scope server model when that lands — no on-disk format change needed.
- Search / filter / sort beyond default reverse-chronological.
- Calendar heatmap and other non-list visualisations.
- Read-only / replay UI separate from `/grille`.
- Multiplayer past games.
- `bliss.tour.seen` erase gap (separate ticket).
- Caching headers (`Cache-Control: max-age=…`) on the list endpoint — defer; the query is cheap.

## Open questions

None. All clarifications resolved during brainstorm.
