# Grid Smart-Focus on Click Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `handleClick` in `useGridNavigation` so a first click on a cell with a fully-filled prefix in one direction routes the player into that clue, before falling back to the existing structural real-start preference.

**Architecture:** Single-file behavioral change in `frontend/src/ui/components/grid/useGridNavigation.ts`. Add a top-level `prefixFilled(clue, position, valuesMap)` helper. In the first-click branch of `handleClick`, prefer clues whose prefix is fully filled (the "smart-start" set) over the existing "real-start" set, then apply the existing tiebreak (single-match wins; current direction sticks; else first by puzzle order).

**Tech Stack:** TypeScript, React 19, Vitest, @testing-library/react. Repo is at the new worktree `/Users/isho/.config/superpowers/worktrees/bliss/feat-grid-smart-focus`, branch `feat/grid-smart-focus`, based on `origin/main`.

**Spec:** `docs/superpowers/specs/2026-05-17-grid-smart-focus-design.md`

---

### Task 1: Pin the desired behavior with a failing test

**Files:**
- Modify: `frontend/tests/grid-input.test.tsx` (append a new `describe` block at the end of the file, before the final `});` of the existing top-level `describe`)

This task adds a dedicated fixture and the **single failing test** that drives the implementation. The fixture needs a cell that is (a) at index ≥ 2 of an across word so its prefix has multiple cells, and (b) the start of a down word, so the existing real-start logic and the new smart-start logic disagree on the answer.

- [ ] **Step 1: Add the SMART_PUZZLE fixture**

Add this constant just below the existing `TEST_PUZZLE` block (around line 62 of `frontend/tests/grid-input.test.tsx`):

```typescript
// Fixture for smart-focus tests. 5×4 grid laid out so cell (1,3) is the
// middle of an across word (across-B, starting at (1,1)) AND the first
// letter of a down word (down-A). Smart-focus widens the candidate set
// when the prefix of across-B (cells (1,1) and (1,2)) is fully filled.
//
//   D→  X   X   D↓  X      across-A: (0,1),(0,2);  down-A: (1,3),(2,3),(3,3)
//   D→  X   X   X   X      across-B: (1,1),(1,2),(1,3),(1,4)
//   X   X   X   X   X
//   X   X   X   X   X
const SMART_PUZZLE: Puzzle = {
  id: 'smart', title: 'smart', language: 'fr', width: 5, height: 4, hintsAllowed: 3,
  cells: [
    { kind: 'definition', position: { row: 0, col: 0 }, clues: [{ text: 'across-A', arrow: 'right' }] },
    L(0, 1), L(0, 2),
    { kind: 'definition', position: { row: 0, col: 3 }, clues: [{ text: 'down-A', arrow: 'down' }] },
    L(0, 4),
    { kind: 'definition', position: { row: 1, col: 0 }, clues: [{ text: 'across-B', arrow: 'right' }] },
    L(1, 1), L(1, 2), L(1, 3), L(1, 4),
    L(2, 0), L(2, 1), L(2, 2), L(2, 3), L(2, 4),
    L(3, 0), L(3, 1), L(3, 2), L(3, 3), L(3, 4),
  ],
};
```

- [ ] **Step 2: Add a new `describe` block with the failing test**

Append this `describe` block AFTER the closing `});` of the existing `describe('Grid keyboard interactions', ...)` block (the closing brace at the bottom of the file). Place it at the same nesting level — a sibling describe inside the file, NOT nested inside the existing describe.

```typescript
// Smart-focus on first click. When the player has filled the prefix of a
// word that passes through the clicked cell, the click should route them
// into THAT word — even if the cell is the structural start of a
// perpendicular word. Without smart-focus, the existing handleClick
// picks the perpendicular real-start, kicking the player off the streak
// they were on. See docs/superpowers/specs/2026-05-17-grid-smart-focus-design.md.
describe('Grid smart-focus on click', () => {
  it('routes click into the clue with a fully filled prefix', () => {
    // Setup: type 'H' 'E' on across-B so cells (1,1) and (1,2) are
    // filled. Auto-advance leaves focus at (1,3) with direction
    // 'across'. Then click (1,3) — which is also the first letter of
    // down-A. Smart-focus must keep direction 'across' because
    // across-B's prefix is fully filled at (1,3).
    const { container } = render(<Grid puzzle={SMART_PUZZLE} />);
    const start = inputAt(container, 1, 1)!;
    click(start);
    typeChar(start, 'h');
    const c12 = inputAt(container, 1, 2)!;
    typeChar(c12, 'e');
    // After two auto-advances, focus is at (1,3). Click it.
    const c13 = inputAt(container, 1, 3)!;
    click(c13);
    // across-B remains current → cells (1,4) along the row are in-word,
    // and down-A's cells (2,3)/(3,3) are NOT in-word.
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 2, 3)?.dataset.inWord).toBe('false');
    expect(wrapAt(container, 3, 3)?.dataset.inWord).toBe('false');
    expect(defAt(container, 1, 0)?.dataset.currentClue).toBe('true');  // across-B
    expect(defAt(container, 0, 3)?.dataset.currentClue).toBe('false'); // down-A
  });
});
```

- [ ] **Step 3: Run the test and verify it fails**

```bash
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-smart-focus/frontend
pnpm test grid-input -- --reporter=verbose
```

Expected: the new test `routes click into the clue with a fully filled prefix` **FAILS**. The existing handleClick picks down-A because it's the structural real-start at (1,3) → down-A becomes current, so the assertion `wrapAt(container, 2, 3)?.dataset.inWord` returns `'true'` instead of the expected `'false'`. All existing tests continue to pass.

- [ ] **Step 4: Commit the failing test**

```bash
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-smart-focus
git add frontend/tests/grid-input.test.tsx
git commit -s -m "test(frontend-grid): pin smart-focus routes click into filled-prefix clue"
```

The commit goes in on red intentionally — the next task makes it green. This isolates the behavior-change diff for review.

---

### Task 2: Implement smart-start in `handleClick`

**Files:**
- Modify: `frontend/src/ui/components/grid/useGridNavigation.ts` — add a top-level helper near `same`/`posOf`/`key` (~line 110), and modify the first-click branch of `handleClick` (~lines 443–458).

- [ ] **Step 1: Add the `prefixFilled` helper**

In `frontend/src/ui/components/grid/useGridNavigation.ts`, add this helper directly after the `LETTER_RE` constant (line 111) and before the `stripDiacritics` function (line 121):

```typescript
// True when every letter cell of `clue` strictly before `p` carries a
// filled value in `values`. The clicked cell's own value and any cells
// after it are irrelevant. When `p` is the first cell of `clue` the
// prefix is empty and the predicate is vacuously true — so every
// real-start automatically also counts as a smart-start. Returns false
// defensively if `p` is not part of `clue.cells` (callers should only
// pass clues actually passing through `p`).
const prefixFilled = (
  clue: Clue,
  p: Position,
  values: Map<string, string>,
): boolean => {
  const idx = clue.cells.findIndex((c) => same(c.position, p));
  if (idx < 0) return false;
  for (let i = 0; i < idx; i++) {
    if (!values.has(key(clue.cells[i].position))) return false;
  }
  return true;
};
```

Note: the `Clue` type is declared lower in the file (line 9) but is hoisted within the module for type position references, so the helper's signature is fine here. If TypeScript complains about ordering, move the helper to just before `useGridNavigation` (around line 287) instead.

- [ ] **Step 2: Modify the first-click branch of `handleClick`**

Find the existing first-click branch in `handleClick` (around lines 443–458):

```typescript
      } else {
        // First click on this cell — prefer a clue that STARTS here. When
        // the user taps the first letter of a word, that word's clue takes
        // precedence over the user's previous direction; otherwise typing
        // would pick up mid-answer in an unrelated clue that happens to
        // pass through. Cells with NO starting clue fall back to the
        // existing "pick by current direction / first clue" logic.
        const starting = allClues.filter((c) => same(c.cells[0].position, p));
        const candidates = starting.length > 0 ? starting : allClues;
        if (candidates.length === 1) {
          next = candidates[0].direction;
        } else if (candidates.length > 1) {
          const onCurrent = candidates.some((c) => c.direction === dir);
          next = onCurrent ? dir : candidates[0].direction;
        }
      }
```

Replace it with this block (inserts a `smart` filter and prefers it over `starting`):

```typescript
      } else {
        // First click on this cell — pick a clue using a three-tier
        // priority. Tier 1 (smart-start): clues whose every letter cell
        // before `p` is already filled. The player has been working on
        // that word, so the click should keep them there. Tier 2
        // (real-start): structural word-boundary preference (the cell is
        // the first letter of the clue). Tier 3: every clue passing
        // through `p`. Real-start is a subset of smart-start (empty
        // prefix is vacuously filled), so smart-start widens the
        // candidate set rather than overriding real-start when both
        // apply — the existing tiebreak then decides.
        const values = cellValuesRef.current;
        const smart = allClues.filter((c) => prefixFilled(c, p, values));
        const starting = allClues.filter((c) => same(c.cells[0].position, p));
        const candidates = smart.length > 0
          ? smart
          : starting.length > 0 ? starting : allClues;
        if (candidates.length === 1) {
          next = candidates[0].direction;
        } else if (candidates.length > 1) {
          const onCurrent = candidates.some((c) => c.direction === dir);
          next = onCurrent ? dir : candidates[0].direction;
        }
      }
```

Note: `cellValuesRef` is in scope inside `handleClick` via the closure (it's declared at the top of `useGridNavigation`, line 319).

- [ ] **Step 3: Run the test and verify it passes**

```bash
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-smart-focus/frontend
pnpm test grid-input -- --reporter=verbose
```

Expected: the new test `routes click into the clue with a fully filled prefix` **PASSES**. All existing tests in `grid-input.test.tsx` continue to pass.

- [ ] **Step 4: Run the full frontend test suite**

```bash
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-smart-focus/frontend
pnpm test
```

Expected: PASS. No regressions in any other test file (`grid-render`, `grid-presence`, `grid-navigation-home-end`, `grid-remote-cell-update`, `multiplayer-highlights`, etc.).

- [ ] **Step 5: Commit the implementation**

```bash
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-smart-focus
git add frontend/src/ui/components/grid/useGridNavigation.ts
git commit -s -m "feat(frontend-grid): route first click into clue with filled prefix"
```

---

### Task 3: Add the supporting test cases

**Files:**
- Modify: `frontend/tests/grid-input.test.tsx` — extend the `describe('Grid smart-focus on click', ...)` block from Task 1.

These tests pin the surrounding contract: regression guard for the "no smart-start" case, the partial-prefix case, and the tiebreak when both directions have smart-start.

- [ ] **Step 1: Add the empty-prefix regression guard**

Inside the `describe('Grid smart-focus on click', ...)` block, append:

```typescript
  it('with empty prefix, still routes to the perpendicular real-start (regression guard)', () => {
    // No typing — cells (1,1) and (1,2) are empty. across-B has no
    // smart-start at (1,3) because the prefix is empty (and (1,3) is
    // not the first cell of across-B). down-A is a real-start →
    // vacuously a smart-start. Only down-A is a candidate → switch
    // direction to down.
    const { container } = render(<Grid puzzle={SMART_PUZZLE} />);
    click(inputAt(container, 1, 1)!);  // direction = 'across'
    click(inputAt(container, 1, 3)!);
    expect(wrapAt(container, 2, 3)?.dataset.inWord).toBe('true');  // down-A
    expect(wrapAt(container, 3, 3)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('false'); // not across-B
    expect(defAt(container, 0, 3)?.dataset.currentClue).toBe('true');  // down-A def
    expect(defAt(container, 1, 0)?.dataset.currentClue).toBe('false'); // across-B def
  });
```

- [ ] **Step 2: Add the partial-prefix case**

Inside the same describe block:

```typescript
  it('partial prefix does not trigger smart-start', () => {
    // Fill only (1,1), leave (1,2) empty. across-B prefix at (1,3) has
    // a gap → smart-start fails. down-A vacuously smart-start → wins.
    const { container } = render(<Grid puzzle={SMART_PUZZLE} />);
    click(inputAt(container, 1, 1)!);
    typeChar(inputAt(container, 1, 1)!, 'h');
    // Auto-advance landed focus on (1,2); click (1,3) directly without
    // typing into (1,2).
    click(inputAt(container, 1, 3)!);
    expect(wrapAt(container, 2, 3)?.dataset.inWord).toBe('true');  // down-A wins
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('false');
    expect(defAt(container, 0, 3)?.dataset.currentClue).toBe('true');
  });
```

- [ ] **Step 3: Add the both-directions-smart-start tiebreak case**

Inside the same describe block:

```typescript
  it('both directions smart-start: current direction sticks via tiebreak', () => {
    // Fill across-B's prefix to make across-B a smart-start at (1,3).
    // down-A is always smart-start at (1,3) (vacuous). Move direction to
    // 'down' before the click by tapping a cell that's only on down-A
    // (cell (2,3) — row 2 has no across clue, so the only clue
    // through (2,3) is down-A). Then click (1,3): both candidates
    // include the current direction (down) → keep down.
    const { container } = render(<Grid puzzle={SMART_PUZZLE} />);
    click(inputAt(container, 1, 1)!);
    typeChar(inputAt(container, 1, 1)!, 'h');
    typeChar(inputAt(container, 1, 2)!, 'e');
    // Direction is now 'across' with focus on (1,3). Click (2,3) — a
    // down-only cell — to set direction = 'down'.
    click(inputAt(container, 2, 3)!);
    expect(wrapAt(container, 1, 3)?.dataset.inWord).toBe('true');  // down-A
    expect(wrapAt(container, 3, 3)?.dataset.inWord).toBe('true');
    // Now click (1,3). Both across-B and down-A are smart-starts. Current
    // direction (down) matches down-A → keep down.
    click(inputAt(container, 1, 3)!);
    expect(wrapAt(container, 2, 3)?.dataset.inWord).toBe('true');  // still down
    expect(wrapAt(container, 3, 3)?.dataset.inWord).toBe('true');
    expect(wrapAt(container, 1, 4)?.dataset.inWord).toBe('false'); // not across
    expect(defAt(container, 0, 3)?.dataset.currentClue).toBe('true');  // down-A
  });
```

- [ ] **Step 4: Run the tests and verify all four smart-focus cases pass**

```bash
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-smart-focus/frontend
pnpm test grid-input -- --reporter=verbose
```

Expected: the four tests under `Grid smart-focus on click` all PASS, alongside every test under `Grid keyboard interactions`.

- [ ] **Step 5: Commit the supporting tests**

```bash
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-smart-focus
git add frontend/tests/grid-input.test.tsx
git commit -s -m "test(frontend-grid): cover smart-focus tiebreak, partial prefix, regression"
```

---

### Task 4: Verify the full quality gate locally

**Files:** none.

Run every gate the CI workflow runs for the frontend so this branch lands ready.

- [ ] **Step 1: Run the full frontend test suite**

```bash
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-smart-focus/frontend
pnpm test
```

Expected: PASS, no skipped tests beyond the pre-existing skip list.

- [ ] **Step 2: Run typecheck**

```bash
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-smart-focus/frontend
pnpm typecheck
```

Expected: PASS with no errors. If the `prefixFilled` helper's forward reference to `Clue` errors out, move the helper to just before the `useGridNavigation` function definition (line 287 area) — `Clue` is declared above that point.

- [ ] **Step 3: Run lint**

```bash
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-smart-focus/frontend
pnpm lint
```

Expected: PASS with no new warnings or errors in `useGridNavigation.ts` or `grid-input.test.tsx`.

- [ ] **Step 4: If any gate fails, fix in place**

Address the failing gate's output before continuing. Re-run the failing gate to verify, then the next gate down the list. Do not skip or downgrade lint/typecheck.

---

### Task 5: Manual smoke check in the browser

**Files:** none.

A short manual run to confirm the click behavior matches expectations against a real puzzle, not just the inline fixture.

- [ ] **Step 1: Start the dev server**

```bash
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-smart-focus
make dev
```

Wait for the Vite output to print the local URL (typically `http://localhost:5173`). The grid-API also boots on port 7777 / 7778 — the dev rule in CLAUDE.md uses `FORCE=1` if those ports are taken.

- [ ] **Step 2: Open a daily puzzle and exercise the click rule**

Open the dev URL in a browser, navigate to a daily puzzle, and verify by hand:

1. **Smart-focus engages:** click into an across word, type 2–3 letters, then click the next empty cell along that row. If the empty cell is also a down-start, the player must remain on across (the across word's highlight stays; the down word's highlight does not appear).
2. **Empty-prefix preserved:** in a fresh puzzle, click the first empty cell of a multi-clue intersection without typing first. The down (or perpendicular) word takes focus — same as before the change.
3. **Repeat-click toggle unchanged:** click any intersection cell twice in a row. Direction flips on the second click regardless of fill state.

If anything diverges from these expectations, stop and revisit the implementation or the spec rather than tweaking until it "feels right."

- [ ] **Step 3: Stop the dev server**

`Ctrl+C` the `make dev` process.

---

### Task 6: Push the branch and open a PR

**Files:** none.

- [ ] **Step 1: Confirm the working tree is clean and the commit log is what we expect**

```bash
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-smart-focus
git status
git log --oneline origin/main..HEAD
```

Expected: clean working tree. Four commits since `main` (in chronological order):
1. `docs(frontend-grid): spec smart-focus on first click` (already on the branch from brainstorming)
2. `test(frontend-grid): pin smart-focus routes click into filled-prefix clue`
3. `feat(frontend-grid): route first click into clue with filled prefix`
4. `test(frontend-grid): cover smart-focus tiebreak, partial prefix, regression`

- [ ] **Step 2: Push the branch**

```bash
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-smart-focus
git push -u origin feat/grid-smart-focus
```

- [ ] **Step 3: Open a PR**

```bash
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-smart-focus
gh pr create --title "feat(frontend-grid): smart-focus first-click on clue with filled prefix" --body "$(cat <<'EOF'
## Summary
- First click on a cell now routes the player into a clue whose prefix is fully filled, even when the cell is the structural start of a perpendicular word.
- Empty prefix and partial prefix cases preserve today's "perpendicular real-start wins" behavior.
- Single-file change in `useGridNavigation.handleClick`; no schema, no domain, no new state.

Spec: `docs/superpowers/specs/2026-05-17-grid-smart-focus-design.md`

## Test plan
- [ ] `pnpm test grid-input` — four new cases under `Grid smart-focus on click`
- [ ] `pnpm test` — full suite green
- [ ] `pnpm typecheck`
- [ ] `pnpm lint`
- [ ] Manual: typing on an across word and clicking the next empty cell (which is also a down-start) keeps focus on across.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: a PR URL prints to stdout. CI's `claude-code-review`, `frontend-build`, `openapi-typescript-drift`, `branch-name`, `dco`, `commitlint`, `codeql`, and `dependency-review` workflows kick off automatically.

---

## Notes for the implementer

- The repo uses Conventional Commits with bounded-context scope; commit-lint rejects bare `wip(...)` types. Stick to the messages quoted above.
- DCO is enforced: every commit must be `-s` (signed-off-by line). The commands above include `-s`.
- Hooks must not be skipped: no `--no-verify`, no `--no-gpg-sign`. If a hook fails, fix the underlying issue; do not bypass.
- All work happens inside the worktree at `/Users/isho/.config/superpowers/worktrees/bliss/feat-grid-smart-focus`. Do not touch the primary checkout.
- The brainstorming step has already committed the spec on this branch as commit `04ffbef3`. The PR will include it.
