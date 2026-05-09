# Hint = letter reveal

**Status**: design approved 2026-05-08, implemented in feat/hint-letter-reveal (PR #249).

## Why

The current hint affordance answers "is this word in the corpus?" — it costs a credit but yields almost no progress on a stuck puzzle. A letter reveal turns the same per-puzzle budget into something the player will actually spend: it converts a hint credit into a guaranteed correct cell. The user-facing motion stays the same (one toolbar button, finite budget, lock once spent), only the payload changes.

## Scope

- Solo mode only.
- Multiplayer co-op reveal (broadcasting the locked cell over the lobby WS) is **out of scope**. The lobby protocol has no "cell-locked" event today, and adding one is a real product decision (does the hinter pay for everyone? does the lock travel?). It gets its own ADR. In the multiplayer route, the existing word-existence hint stays in place until that decision lands.
- Out of scope: visual designer-grade polish on the locked-cell treatment. We'll pick a tasteful default (muted-accent background + `readOnly`) and iterate if needed.

## API

### Request shape — `POST /v1/puzzles/{puzzleId}/hints`

```json
{ "row": 3, "column": 5 }
```

- `row` and `column` are zero-indexed grid coordinates.
- Header `X-Session-Id: <uuid v7>` unchanged (player session, hint-budget key).
- Content-Type `application/json` unchanged.

### Response shape (200)

```json
{ "row": 3, "column": 5, "letter": "A", "hintsRemaining": 2 }
```

- `letter` is the canonical solution at that cell, uppercase, single Unicode letter (matches the validate endpoint's `letter` shape).
- `hintsRemaining` is the post-decrement count (server-authoritative).

### Errors

| Status | `type` | When |
|---|---|---|
| 400 | `…/invalid-coord` | (row, column) is out of bounds, or points to a non-letter cell (clue cell, black cell). Hint **not** decremented. |
| 400 | `…/invalid-session-id` | `X-Session-Id` missing or not UUID. Unchanged. |
| 429 | `…/budget-exhausted` | Budget already at zero before this call. Body echoes `hintsRemaining: 0`. Unchanged semantics. |

The previous `{ word, exists }` request/response is **replaced**, not deprecated. The only caller is the frontend in this repo. Pre-release contract — clean break is acceptable; CHANGELOG entry will note it.

### Anti-cheat

One letter per call, one credit per letter. Draining the grid still costs `hintsAllowed` calls and is bounded by the per-puzzle budget. No per-cell ledger needed — `puzzle_hint_usage` keeps tracking total spend.

## Backend (`grid/`)

- **OpenAPI spec** (`grid/api/openapi.yaml`): replace request body `HintRequest` and response `HintResponse` schemas. Add `invalid-coord` problem-detail type to the route's `4xx` documentation.
- **Use case**: rename `RequestWordHintUseCase` → `RevealCellHintUseCase`. New signature accepts `(puzzleId, sessionId, row, column)`. Behaviour:
  1. Load the puzzle; resolve the cell at (row, column).
  2. If absent or not a letter cell → return `Failure(InvalidCoord)`.
  3. Atomically increment `puzzle_hint_usage` for (puzzle, session). If post-increment count exceeds `hintsAllowed`, return `Failure(BudgetExhausted)` and do not increment beyond the cap.
  4. Look up the canonical solution letter at (row, column) from the same puzzle.
  5. Return `Success({ row, column, letter, hintsRemaining })`.
- **Repository**: `PuzzleRepository` already exposes the canonical solution to `ValidatePuzzleUseCase`; the same path is reused. No new SQL.
- **Migration**: none. `puzzle_hint_usage` schema unchanged.
- **Route** (`PuzzleRoute.kt`): map `InvalidCoord` to a 400 problem-detail. The existing 400 mapping for `invalid-session-id` and 429 for `budget-exhausted` stay.
- **Tests** (`HintsRouteTest`):
  - 200 happy path returns the letter at a valid letter cell, decrements budget.
  - 400 `invalid-coord` for out-of-bounds row/column.
  - 400 `invalid-coord` for clue/black cell.
  - 400 `invalid-session-id` (existing) unchanged.
  - 429 `budget-exhausted` (existing) unchanged.
  - Use case unit tests mirror the same matrix against an `InMemoryPuzzleRepository` + `InMemoryHintUsageRepository`.

## Frontend (`frontend/`)

### Generated types
Regen `frontend/src/infrastructure/api/grid/types.ts` after the OpenAPI change (`pnpm api:generate`). Diff is exempt from the line cap (CLAUDE.md / ADR-0001 §4).

### Application port
`PuzzleSolver.requestHint`:

```ts
requestHint(puzzleId: string, row: number, column: number): Promise<HintResult>;

interface HintResult {
  readonly row: number;
  readonly column: number;
  readonly letter: string;
  readonly hintsRemaining: number;
}
```

`HintRequestError` adds an `invalid-coord` kind alongside the existing `budget-exhausted | invalid-word | transient`. The `invalid-word` kind is removed in the same commit (no caller left).

### Adapter
`HttpPuzzleSolver.requestHint` builds the new body and maps the new error shape. Status-code routing per ADR-0003 §6 (we don't parse `error.type` URIs).

### Hook (`useHintRequest`)
- Signature changes: `request(row: number, column: number)` instead of `request(word: string)`. The hook no longer cares about the active *word*; it cares about the focused *cell*.
- On success it surfaces `lastResult: { row, column, letter }` (instead of `{ word, exists }`) so the consumer can both apply the letter to the DOM input and mark the cell as locked.
- The pending guard, sequence-number-based stale-result drop, and 4 s pill linger all stay.
- Reset-on-puzzle-change behaviour stays.

### Component (`HintControl`)
- Replace the `getCurrentWord(): string | null` callback with `getFocusedCell(): { row: number; column: number; isLocked: boolean } | null`.
- Click handler: if `getFocusedCell()` returns null, no-op. Otherwise call `request(row, column)`.
- Disabled rule: `exhausted || pending || focusedCell === null || focusedCell.isLocked`.
- The `onMouseDown` → `preventDefault()` keep-focus fix from the previous turn stays.
- Status pill copy:
  - success → `Lettre révélée` (4 s linger)
  - 429 → `Indices épuisés` (sticky until puzzle change, matches today)
  - 400 invalid-coord → silent no-op (no pill). Only reachable via a stale focus race; not user-facing signal.
  - other → `Erreur, réessayez` (4 s linger).

### Grid cells
- Each letter cell gains a `locked: boolean`. The lock map lives next to the existing entry map in the grid container so the two evolve together; the implementation plan pins down the exact module.
- Locked cells render with:
  - `readOnly` on the underlying `<input>` so keystrokes can't overwrite them.
  - A muted-accent background (Panda token; pick at impl time, e.g. `colors.accentMuted`).
  - The same monospace glyph treatment as a normal letter — the cell is visibly *filled and unfightable*, not a different shape.
- The reveal flow writes the letter via `el.value = letter` (uncontrolled-input contract, ADR-0002 §4) and then flips the lock flag. No top-level re-render in the keystroke path is regressed.
- Auto-validation (PR #245) treats locked cells as filled; since they're correct by construction, the validator naturally agrees. No special-casing needed.

### Persistence
- Solo localStorage shape extends from `{ letters }` to `{ letters, lockedCells }`, where `lockedCells` is an array of `{ row, column }` (small, JSON-friendly).
- On read, if the stored blob lacks `lockedCells`, treat it as empty. If the blob is malformed (parse error or wrong shape), drop and start fresh — same defensiveness as today.
- No version bump required because the new field is purely additive.

### Tests
- `useHintRequest` unit tests rewritten for the new contract: success applies the letter, 429 locks the budget, 400 invalid-coord surfaces transient pill, sequence-number drops stale 200s.
- `HintControl` test matrix:
  - disabled when no cell focused
  - disabled when focused cell is locked
  - enabled-and-fires when focused cell is unlocked (regardless of typed-correctness — see "edge case" below)
  - mousedown does not steal focus from the cell input (keep the existing assertion)
- Grid cell test: a locked cell rejects keystrokes (`fireEvent.input` on a `readOnly` input is a no-op).
- Solo persistence test: round-trip a `{ letters, lockedCells }` blob across a remount; locked cells stay locked, letters stay filled.

## Edge case: typed-correct, not locked

The button does not auto-disable for cells the user happened to type correctly. The client doesn't know per-cell correctness without an extra `validate` call — the auto-validate path runs per word, but threading per-cell validity to the hint button isn't free. Clicking on a typed-correct cell costs a hint and locks it. Documented behavior, not a bug. Revisit if user testing shows people regularly burning hints this way.

## Out of scope (deferred)

- Multiplayer broadcast of locked cells (separate ADR — co-op reveal semantics).
- Anti-cheat: rate-limiting reveal velocity beyond the per-puzzle budget.
- Visual treatment beyond a sensible default for the locked cell.
- Surfacing "letters revealed" as a metric on the puzzle-completion screen.

## Test strategy summary

| Layer | What we add |
|---|---|
| `grid:domain` (none new) | — |
| `grid:application` | `RevealCellHintUseCase` unit tests: happy path, invalid-coord (OOB, non-letter), budget-exhausted boundary. Real `InMemoryPuzzleRepository` + `InMemoryHintUsageRepository`. |
| `grid:api` | `HintsRouteTest` (rewritten): 200, 400 invalid-coord ×2, 400 invalid-session-id, 429 budget-exhausted, X-Session-Id header propagation. |
| frontend application | `useHintRequest` rewrite tests against a fake `PuzzleSolver`. |
| frontend ui | `HintControl` disabled/enabled matrix, locked-cell read-only assertion, solo persistence round-trip. |

No e2e additions — the existing puzzle solo-mode e2e exercises the toolbar and is enough once the unit/integration set is green.

## Risks

- **OpenAPI regen drift**: regen-and-diff CI fails if the consumer PR forgets `pnpm api:generate`. Standard checklist item.
- **Locked-cell visual** clashes with cell highlighting (selected word, validate-incorrect tint). Order of styles needs to be: locked > validate-state > selected > base. Pick the right `cx()` order at impl time.
- **localStorage shape extension** could collide with concurrent in-flight saves if the user opens two tabs. The existing implementation already last-write-wins; this doesn't make it worse, but neither does it fix it.
