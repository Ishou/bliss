# PR-B1 — skip link + grid keyboard polish

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the keyboard / focus polish piece of the a11y epic: Home/End to jump to word boundaries inside the grid; per-route skip-link copy; a second skip link on grille that lands on the first non-validated cell.

**Architecture:** Pure UI changes under `frontend/src/ui/`. `useGridNavigation` gains two new key handlers; `AppHeader` reads the current pathname and switches the existing skip link's label and target by route family; `grille.tsx` adds a second visually-hidden anchor that focuses the first writable cell input. Verification by vitest (Home/End logic) + Playwright e2e (skip-link focus targets, cold-load focus stays on body).

**Tech Stack:** TypeScript, React 19, TanStack Router, Panda CSS, Vitest + Testing Library, Playwright.

---

## Spec reference

`docs/superpowers/specs/2026-05-10-a11y-foundation-design.md` — section 3 PR-B1 row, section 6 (skip link + focus rules). Some spec line items already shipped on `main`; reconciliation below.

## Existing state on `main` (verified pre-plan)

Already implemented — **do not re-do**:

- Skip link in `AppHeader.tsx`: visually-hidden anchor with `_focusVisible` reveal, `href="#main-content"`, label `« Aller au contenu principal »`. Target id `#main-content` exists on `<main tabIndex={-1}>` in `accueil.tsx`, `grille.tsx`, and `lobby.$lobbyId.tsx`.
- `useGridNavigation` Tab / Shift-Tab cycling between words.
- `useGridNavigation` Arrow keys, Space (toggle direction), Backspace.

Gaps this PR fills:

- **Home/End keys** — jump to first/last cell of the current word.
- **Per-route skip-link label** — *« Aller à la grille »* on `/grille`, *« Aller au contenu »* elsewhere.
- **Second skip link on grille** — *« Aller au mot fléché »*, focuses the first non-validated cell.

Out of scope (deferred to later PRs in the epic):

- Screen-reader announcements (PR-B2).
- Modal-close focus restoration verification — ark-ui Dialog already handles the standard case; a dedicated edge-case test is deferred unless a real bug surfaces during this PR.
- "Nouvelle grille chargée" announcement on puzzle reload (PR-B2; uses the announcer that doesn't exist yet).

## File structure

| Path | Action | Purpose |
|---|---|---|
| `frontend/src/ui/components/grid/useGridNavigation.ts` | **modify** | Add `case 'Home'` and `case 'End'` to the keydown switch. |
| `frontend/tests/grid-navigation-home-end.test.tsx` | **create** | Vitest red→green for Home/End. |
| `frontend/src/ui/components/layout/AppHeader.tsx` | **modify** | Resolve label + target from current pathname; expose two skip-link variants. |
| `frontend/src/ui/routes/grille.tsx` | **modify** | Add the second visually-hidden skip link + helper that focuses the first non-validated cell. |
| `frontend/e2e/a11y-keyboard.spec.ts` | **create** | Playwright e2e: skip-link copy + focus targets per route; cold-load focus stays on `<body>`. |

Total estimated diff: ~150–250 lines added, well inside the 400 LoC manifesto rule.

---

## Task 1: Home/End keys for word-boundary navigation

**Files:**
- Create: `frontend/tests/grid-navigation-home-end.test.tsx`
- Modify: `frontend/src/ui/components/grid/useGridNavigation.ts`

**Spec:** Pressing Home moves focus to the **first cell of the current word** (does not change direction). Pressing End moves to the **last cell of the current word**. No-op when no cell is focused. preventDefault on both so the browser's default Home/End behaviour (scroll to top/bottom of input) doesn't fire on the active `<input>`.

The current word's cells are exposed by `currentClue.cells` (already computed). The first cell of the word is `currentClue.cells[0]`; the last is `currentClue.cells[currentClue.cells.length - 1]`. Focusing a cell is the same path the existing arrow handlers use — they call `focusCell(row, col)` (or equivalent). Read the existing arrow case to mirror the exact call shape.

- [ ] **Step 1.1: Find the existing arrow-handler call shape**

Run: `grep -nE "case 'ArrowRight'|focusCell|focusInputAt|setFocused" /Users/isho/IdeaProjects/bliss-monospace-clue/frontend/src/ui/components/grid/useGridNavigation.ts | head -15`

Identify the function/method the arrow case calls to move focus to a `(row, col)`. Use the same call shape in Steps 1.5 / 1.6.

- [ ] **Step 1.2: Read the current keydown switch (around line 564–730)**

Read 600–730 of `useGridNavigation.ts`. Locate the `case 'ArrowRight':` block and the surrounding switch. Note the variable names for the focused cell, the current clue, etc., so the new cases use the same identifiers.

- [ ] **Step 1.3: Write the failing vitest red — Home key**

Create `frontend/tests/grid-navigation-home-end.test.tsx`. Use the same render harness as `frontend/tests/grid-input.test.tsx` (read it first to copy the pattern):

```tsx
import { act, fireEvent, render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Grid } from '@/ui/components/grid';
import type { Puzzle } from '@/domain';

// 5×1 across-only puzzle: cells (0,0)..(0,4). Word: "?????".
// Definition cell at (0,-1) virtual? No — keep it simple: a 1×6 row
// with the def cell at (0,0) and letter cells at (0,1)..(0,5).
const fivePuzzle: Puzzle = {
  id: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  title: 'home/end fixture',
  rows: 1,
  cols: 6,
  cells: [
    { position: { row: 0, col: 0 }, kind: 'definition',
      clues: [{ arrow: 'right', text: 'cinq lettres' }] },
    { position: { row: 0, col: 1 }, kind: 'letter' },
    { position: { row: 0, col: 2 }, kind: 'letter' },
    { position: { row: 0, col: 3 }, kind: 'letter' },
    { position: { row: 0, col: 4 }, kind: 'letter' },
    { position: { row: 0, col: 5 }, kind: 'letter' },
  ],
};

function inputAt(container: HTMLElement, row: number, col: number): HTMLInputElement {
  const el = container.querySelector(
    `input[data-cell-kind="letter"][data-row="${row}"][data-col="${col}"]`,
  );
  if (!el) throw new Error(`no input at (${row},${col})`);
  return el as HTMLInputElement;
}

describe('useGridNavigation — Home/End', () => {
  it('Home moves focus to the first cell of the current word', () => {
    const { container } = render(<Grid puzzle={fivePuzzle} />);
    const middle = inputAt(container, 0, 3);
    act(() => { middle.focus(); });
    fireEvent.keyDown(middle, { key: 'Home' });
    expect(document.activeElement).toBe(inputAt(container, 0, 1));
  });

  it('End moves focus to the last cell of the current word', () => {
    const { container } = render(<Grid puzzle={fivePuzzle} />);
    const middle = inputAt(container, 0, 3);
    act(() => { middle.focus(); });
    fireEvent.keyDown(middle, { key: 'End' });
    expect(document.activeElement).toBe(inputAt(container, 0, 5));
  });

  it('Home is a no-op when nothing is focused', () => {
    const { container } = render(<Grid puzzle={fivePuzzle} />);
    fireEvent.keyDown(container, { key: 'Home' });
    // No assertion failure = passing. Ensures no crash / null-ref.
    expect(document.activeElement).toBe(document.body);
  });
});
```

Note: if the existing fixtures or `Grid` props differ from this minimal shape (e.g., `Grid` requires `validatedPositions`, presence callbacks, etc.), copy the bare-minimum prop set from `frontend/tests/grid-input.test.tsx` to make the harness compile. Do **not** invent props.

- [ ] **Step 1.4: Run the failing test**

Run: `cd /Users/isho/IdeaProjects/bliss-monospace-clue/frontend && pnpm test grid-navigation-home-end -- --run`
Expected: **FAIL** — both Home and End leave focus where it was.

- [ ] **Step 1.5: Implement Home/End in `useGridNavigation`**

In `frontend/src/ui/components/grid/useGridNavigation.ts`, find the `handleKeyDown` switch (around line 651). Add two cases ALONG with the existing arrow cases. Place them adjacent to `case 'ArrowRight':` so the keyboard-routing block is co-located:

```ts
        case 'Home': {
          const f = stateRef.current.focused;
          if (!f) return;
          const clue = currentClueAt(puzzle, f.position, f.direction);
          if (!clue) return;
          const first = clue.cells[0]?.position;
          if (!first) return;
          e.preventDefault();
          focusCellAt(first.row, first.col);
          break;
        }
        case 'End': {
          const f = stateRef.current.focused;
          if (!f) return;
          const clue = currentClueAt(puzzle, f.position, f.direction);
          if (!clue) return;
          const last = clue.cells[clue.cells.length - 1]?.position;
          if (!last) return;
          e.preventDefault();
          focusCellAt(last.row, last.col);
          break;
        }
```

**Adjust the function names** (`currentClueAt`, `focusCellAt`, `stateRef.current.focused`) to match what the file actually uses — those identifiers are placeholders mirroring the existing arrow case. After Step 1.2 you'll know the real names; substitute them here. Do **not** add new helper functions; reuse what the arrow handler reuses.

- [ ] **Step 1.6: Run the test green**

Run: `cd /Users/isho/IdeaProjects/bliss-monospace-clue/frontend && pnpm test grid-navigation-home-end -- --run`
Expected: **PASS** (3 tests).

If only one of Home/End passes, the asymmetry is most likely in how you read `clue.cells` — check that the cells are sorted by position in the order the user types them (col asc for across, row asc for down). The existing `wordRange.ts` helper guarantees this; if you used a different code path, switch to `wordRange`.

- [ ] **Step 1.7: Run the full vitest suite to confirm no regressions**

Run: `cd /Users/isho/IdeaProjects/bliss-monospace-clue/frontend && pnpm test`
Expected: 479/479 pass (476 prior + 3 new).

- [ ] **Step 1.8: Commit**

```bash
git add frontend/src/ui/components/grid/useGridNavigation.ts frontend/tests/grid-navigation-home-end.test.tsx
git commit -s -m "feat(grid): home/end keys jump to current word boundaries"
```

---

## Task 2: Per-route skip-link label in `AppHeader`

**Files:**
- Modify: `frontend/src/ui/components/layout/AppHeader.tsx`

**Spec:** Skip-link label is *« Aller à la grille »* on `/grille` (and lobby grid sub-routes), *« Aller au contenu »* elsewhere. Target stays `#main-content` for both.

The header already calls `useRouterState({ select: (s) => s.location.pathname })` for active-nav resolution — reuse it.

- [ ] **Step 2.1: Add the label resolver**

In `frontend/src/ui/components/layout/AppHeader.tsx`, just above the `function activeIdForPath` declaration, add:

```ts
// Skip-link label tracks the route family. Grid routes get the
// shorter "Aller à la grille" — describes the actual jump target,
// since the grid is the dominant content under <main>. Other routes
// fall back to the generic "Aller au contenu".
const GRID_ROUTE_PATTERNS = [/^\/grille(\/|$)/, /^\/lobby\//];

function skipLinkLabelForPath(pathname: string): string {
  return GRID_ROUTE_PATTERNS.some((re) => re.test(pathname))
    ? 'Aller à la grille'
    : 'Aller au contenu';
}
```

- [ ] **Step 2.2: Use the resolver in the rendered anchor**

Find the existing skip link in the `AppHeader` JSX:

```tsx
      <a href="#main-content" className={skipLinkStyles}>
        Aller au contenu principal
      </a>
```

Replace with:

```tsx
      <a href="#main-content" className={skipLinkStyles}>
        {skipLinkLabelForPath(pathname)}
      </a>
```

`pathname` is already in scope (used by `resolvedActiveId`); no extra hook needed.

- [ ] **Step 2.3: Verify typecheck + lint**

```
cd /Users/isho/IdeaProjects/bliss-monospace-clue/frontend
pnpm typecheck
pnpm lint
```
Expected: PASS.

- [ ] **Step 2.4: Run vitest (regression-safety; no new test added in this task — Task 4 covers e2e)**

Run: `pnpm test`
Expected: all PASS (no app-header test pins the literal copy; if one does, update it as part of this commit and note in the message).

- [ ] **Step 2.5: Commit**

```bash
git add frontend/src/ui/components/layout/AppHeader.tsx
git commit -s -m "feat(a11y): per-route skip-link label (grille vs other routes)"
```

---

## Task 3: Second skip link on grille — *« Aller au mot fléché »*

**Files:**
- Modify: `frontend/src/ui/routes/grille.tsx`

**Spec:** A second visually-hidden anchor on the grille route, label *« Aller au mot fléché »*, that focuses the first non-validated letter cell. Saves the keyboard user from tabbing through the toolbar (zoom, hint, etc.) before reaching the grid.

Mechanism: an `<a>` with an `onClick` handler that imperatively calls `.focus()` on the first non-validated input. Selector: `input[data-cell-kind="letter"]:not([readonly])` — validated cells are rendered read-only by `Cell.tsx` (verified in pre-plan exploration).

The new link must be placed AFTER the `<AppHeader>`'s skip link in tab order (so the user's first Tab lands on the AppHeader skip link, the second on this one). Putting it as the **first child of `<main>`** achieves that: `<main>` is in the tab order via the AppHeader skip link's target, and the link sits before any toolbar content.

Reuse the same `skipLinkStyles` shape — extract it to a shared module rather than duplicating? **No.** The styles are small (~30 lines), and a shared module would force `AppHeader` and `grille.tsx` into a circular import or a new `ui/components/a11y/` dir for one consumer. Inline the styles in `grille.tsx`; extract on the third occurrence (YAGNI).

- [ ] **Step 3.1: Add the styles + handler in `grille.tsx`**

Read `frontend/src/ui/routes/grille.tsx` around line 128 (`<main id="main-content" ...>`). Just inside `<main>`, before the toolbar, insert the link. Add the styles + handler at the top of the route module's style declarations (alongside `pageStyles`, `mainStyles`, etc.):

```tsx
// Same visually-hidden-on-blur, pinned-chip-on-focus pattern as the
// AppHeader skip link. Placed inside <main> so a keyboard user who
// already activated the header skip link can Tab once more and jump
// straight into the grid, bypassing the toolbar.
const skipToGridStyles = css({
  position: 'absolute',
  width: '1px',
  height: '1px',
  margin: '-1px',
  padding: 0,
  overflow: 'hidden',
  clip: 'rect(0, 0, 0, 0)',
  whiteSpace: 'nowrap',
  borderWidth: 0,
  _focusVisible: {
    position: 'fixed',
    top: '8px',
    insetInlineStart: '8px',
    width: 'auto',
    height: 'auto',
    margin: 0,
    paddingBlock: '8px',
    paddingInline: '12px',
    overflow: 'visible',
    clip: 'auto',
    whiteSpace: 'normal',
    bg: 'accent',
    color: 'bg',
    fontFamily: 'body',
    fontSize: 'sm',
    fontWeight: 'medium',
    textDecoration: 'none',
    borderRadius: 'md',
    zIndex: 100,
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
});

function focusFirstUnvalidatedCell(): void {
  // `:not([readonly])` filters out validated cells (Cell.tsx renders
  // them read-only). Falls back to the first letter cell if no cell
  // qualifies — covers the "puzzle fully solved" edge case so the
  // skip link still does something visible.
  const main = document.getElementById('main-content');
  const target =
    main?.querySelector<HTMLInputElement>('input[data-cell-kind="letter"]:not([readonly])')
    ?? main?.querySelector<HTMLInputElement>('input[data-cell-kind="letter"]');
  target?.focus();
}
```

- [ ] **Step 3.2: Render the link inside `<main>`**

Find the `<main id="main-content" tabIndex={-1} ...>` opening tag. Add as its FIRST child (before any toolbar / progress-bar / grid markup):

```tsx
        <a
          href="#main-content"
          className={skipToGridStyles}
          onClick={(e) => {
            e.preventDefault();
            focusFirstUnvalidatedCell();
          }}
        >
          Aller au mot fléché
        </a>
```

The `href="#main-content"` is intentional even though `onClick.preventDefault()` swallows the navigation — it keeps the element a valid anchor (semantics + right-click "open in new tab" doesn't dump the user on a page that won't work; they end up on the same route at `<main>`, which is fine).

- [ ] **Step 3.3: Typecheck + lint**

```
pnpm typecheck
pnpm lint
```
Expected: PASS.

- [ ] **Step 3.4: Commit (no behavioural test in this task — Task 4 covers e2e)**

```bash
git add frontend/src/ui/routes/grille.tsx
git commit -s -m "feat(a11y): skip link on grille jumps to first writable cell"
```

---

## Task 4: e2e regression net — skip-link focus targets + cold-load focus

**Files:**
- Create: `frontend/e2e/a11y-keyboard.spec.ts`

**Spec:** Two behaviour assertions:

1. On `/grille`, the **first** Tab from a fresh body focus lands on the AppHeader skip link with label *« Aller à la grille »*; the **second** Tab lands on the in-`<main>` skip link with label *« Aller au mot fléché »*; activating the second skip link moves focus to a writable letter cell.
2. On cold load of `/grille`, focus stays on `<body>` — the route does not auto-focus the grid (steals the SR's expected reading order).

These run against the existing Playwright preview server + MSW fixture; copy the route-mock + tour-seen pre-seed pattern from `frontend/e2e/a11y.spec.ts`.

- [ ] **Step 4.1: Create the spec**

```ts
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

import { expect, test } from '@playwright/test';

const FIXTURE_PATH = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  '..', 'src', 'infrastructure', 'mocks', 'fixtures', 'puzzle.json',
);
const PUZZLE_FIXTURE = JSON.parse(readFileSync(FIXTURE_PATH, 'utf-8')) as Record<string, unknown>;

async function bootstrap(page: import('@playwright/test').Page): Promise<void> {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.addInitScript(() => {
    window.localStorage.setItem('wordsparrow.tour.seen', 'true');
  });
  await page.route(/\/v1\/puzzles\//, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(PUZZLE_FIXTURE),
    });
  });
  await page.goto('/grille');
  await page.waitForSelector('[role="grid"]', { state: 'visible' });
  await page.evaluate(() => document.fonts.ready);
}

test.describe('a11y keyboard', () => {
  test('cold-load grille keeps focus on body', async ({ page }) => {
    await bootstrap(page);
    const tag = await page.evaluate(() => document.activeElement?.tagName ?? null);
    expect(tag).toBe('BODY');
  });

  test('grille skip links are reachable by keyboard and target the right elements', async ({ page }) => {
    await bootstrap(page);

    // The first Tab from a fresh body always lands on the AppHeader skip
    // link — it is the first focusable element in document order. The
    // grille skip link sits later in tab order (after the lockup + nav
    // links) and is reached by addressing it by accessible name, not
    // by Tab-counting (which is brittle to header structure changes).
    await page.keyboard.press('Tab');
    const headerSkip = await page.evaluate(() => {
      const el = document.activeElement as HTMLAnchorElement | null;
      return el ? { tag: el.tagName, text: el.textContent?.trim() ?? '', href: el.getAttribute('href') } : null;
    });
    expect(headerSkip).toEqual({ tag: 'A', text: 'Aller à la grille', href: '#main-content' });

    // The grille skip link must exist and be reachable; locator() finds
    // it via accessible name even when visually hidden.
    const grilleSkip = page.getByRole('link', { name: 'Aller au mot fléché' });
    await expect(grilleSkip).toHaveCount(1);

    // Programmatic focus + Enter mirrors what a keyboard user does
    // after Tab-walking to it. Avoids depending on the exact Tab count
    // through the header.
    await grilleSkip.focus();
    await page.keyboard.press('Enter');

    const focused = await page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null;
      if (!el) return null;
      return {
        tag: el.tagName,
        kind: el.getAttribute('data-cell-kind'),
        readonly: (el as HTMLInputElement).readOnly,
      };
    });
    expect(focused).toMatchObject({ tag: 'INPUT', kind: 'letter', readonly: false });
  });

  test('accueil header skip link uses generic copy', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.addInitScript(() => {
      window.localStorage.setItem('wordsparrow.tour.seen', 'true');
    });
    await page.route(/\/v1\/puzzles\//, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(PUZZLE_FIXTURE),
      });
    });
    await page.goto('/', { waitUntil: 'networkidle' });

    await page.keyboard.press('Tab');
    const first = await page.evaluate(() => {
      const el = document.activeElement as HTMLAnchorElement | null;
      return el?.textContent?.trim() ?? null;
    });
    expect(first).toBe('Aller au contenu');
  });
});
```

- [ ] **Step 4.2: Run the new spec**

Run: `cd /Users/isho/IdeaProjects/bliss-monospace-clue/frontend && pnpm e2e --project=chromium e2e/a11y-keyboard.spec.ts`
Expected: 3 tests PASS.

If `cold-load grille keeps focus on body` fails (active element is the grid input or `<main>`): the route is auto-focusing the grid somewhere. Find the `.focus()` call (likely in `grille.tsx` or `Grid.tsx`'s mount effect) and either remove it or gate it behind a "user has interacted" condition. Report `BLOCKED` with the exact location if the call is non-obvious; the spec rule is "cold load: nowhere — focus stays on body", and changing autofocus is a real behaviour shift.

If `Tab order` fails because Tab lands on the AppHeader logo `<a>` first instead of the skip link: check the document order — the skip link must be the **first focusable child of `<header>`**. Re-read `AppHeader.tsx` and re-order if needed.

- [ ] **Step 4.3: Run the full a11y suite to confirm no regression**

Run: `pnpm a11y --project=chromium`
Expected: all PASS (4 tests from PR-A2 + 3 new = 7).

- [ ] **Step 4.4: Commit**

```bash
git add frontend/e2e/a11y-keyboard.spec.ts
git commit -s -m "test(a11y): tab order + cold-load focus on grille"
```

---

## Task 5: Final verification (controller)

- [ ] **Step 5.1: Lint, typecheck, vitest, e2e (focused)**

```
cd /Users/isho/IdeaProjects/bliss-monospace-clue/frontend
pnpm lint && pnpm typecheck && pnpm test && pnpm e2e --project=chromium e2e/a11y.spec.ts e2e/a11y-keyboard.spec.ts
```

Expected: ALL PASS.

- [ ] **Step 5.2: Diff size sanity**

```
git -C /Users/isho/IdeaProjects/bliss-monospace-clue diff --stat main...HEAD
```

Expected: under 400 lines net code addition (excluding plan/spec docs and lockfiles).

- [ ] **Step 5.3: Commit log shape**

```
git -C /Users/isho/IdeaProjects/bliss-monospace-clue log --oneline main..HEAD
```

Expected: 4 commits, each conventional + lowercase first word + DCO-signed:

```
xxxxxxx test(a11y): tab order + cold-load focus on grille
xxxxxxx feat(a11y): skip link on grille jumps to first writable cell
xxxxxxx feat(a11y): per-route skip-link label (grille vs other routes)
xxxxxxx feat(grid): home/end keys jump to current word boundaries
```

- [ ] **Step 5.4: PAUSE for user manual sanity**

Per the saved feedback: for UI-touching work, the user manually tests in real Chrome before any push. Hand off:

> Local checks green. Try `/grille` in real Chrome:
> - Tab → AppHeader skip link reads "Aller à la grille"; activate, focus lands on `<main>`.
> - Tab again → "Aller au mot fléché" appears; activate, focus lands inside the grid.
> - Inside a word, press Home / End — focus snaps to the first / last cell of that word.
> - Reload — confirm no element auto-focuses; focus stays on body.
>
> Once you're happy, push and open the PR.

---

## Decision branches (referenced from earlier tasks)

### If the cold-load focus test (Step 4.2) fails

- The grille route auto-focuses some cell on mount. Find the `.focus()` call. Likely candidates: `Grid.tsx` mount effect, `useGridNavigation` mount effect, the puzzle-loaded effect in `grille.tsx`.
- If the auto-focus is intentional for sighted UX, the right fix is to skip it when the user hasn't interacted yet (e.g., gate on `document.hasFocus() && !document.activeElement?.matches('input, button, a')` — i.e., body).
- If reproducing on production today shows the same auto-focus, it's a pre-existing bug that this PR's spec asks to fix; do it inline and append `+ fix: skip auto-focus on cold load` to Task 4's commit message.

### If a vitest from a different file fails after Task 1

`useGridNavigation.ts` is large and shared; new key cases shouldn't regress arrow / tab / space behaviour, but if they do, the most likely culprit is shadowing a variable name. Re-read your diff against the surrounding switch and align names.

### If typecheck fails on the new test fixture in Step 1.3

The `Puzzle` type (`@/domain`) almost certainly requires more fields than the minimal fixture above (e.g., `gridLayout`, `solution`, additional `DefinitionCell` fields). Read `frontend/tests/grid-input.test.tsx` for a working minimal `Puzzle` and copy the missing fields. Do **not** invent shapes — use the existing test fixture as ground truth.

---

## Out of scope (PR-B1 will not include)

- Screen-reader live region announcements (PR-B2).
- Multiplayer presence announcements (PR-B2).
- Cell-aria-label restructure (PR-B2).
- Modal-close opener-unmounted edge-case test (defer until a real bug surfaces).
- Auto-focus-on-load fix (only if Step 4.2 surfaces it; otherwise out).
- Re-announce hotkey (deferred per ADR-0034 §7).
