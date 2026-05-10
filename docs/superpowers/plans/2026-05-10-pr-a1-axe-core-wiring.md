# PR-A1 — axe-core wiring + a11y baseline

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the WCAG 2.2 AA automated gate. Extend the existing axe-core e2e to two new routes, apply a severity policy (fail on `serious`/`critical`, log on `moderate`, ignore `minor`), expose a `vitest-axe` matcher for component tests, expose a `pnpm a11y` script, and document the baseline as ADR-0034.

**Architecture:** Single PR, all changes under `frontend/` + `docs/adr/`. The existing `frontend/e2e/a11y.spec.ts` already runs `@axe-core/playwright` against the home route with strict WCAG 2.0 A/AA enforcement. We refactor it into reusable helpers, add the severity-filtered assertion, expand the tag set to include WCAG 2.1 A/AA + 2.2 AA, and add two more routes: not-found and the multi waiting room. Component-level a11y is unlocked via `vitest-axe`'s matcher in a tiny `src/test/a11y.ts` helper; tests land in PR-A2 as they fix specific components. CI is unchanged — existing `pnpm test` and `pnpm e2e` jobs pick up new specs automatically.

**Tech Stack:** TypeScript, Playwright + `@axe-core/playwright` (already installed), Vitest + Testing Library + `vitest-axe` (new), MSW for API/WS mocking (already wired), TanStack Router.

---

## Spec reference

`docs/superpowers/specs/2026-05-10-a11y-foundation-design.md` — sections 3 (PR-A1 row), 4 (tooling stack), 8 (ADR outline), 9 (manifesto compliance), 10 (TDD ordering).

## Existing state (baseline before this PR)

- `frontend/e2e/a11y.spec.ts` exists, scans `/` (home), tag set `['wcag2a','wcag2aa']`, asserts `expect(summary).toEqual([])` — strict, no severity filter, no impact-based gating. Already imports `AxeBuilder` from `@axe-core/playwright`.
- `@axe-core/playwright@^4.11.3` installed.
- Multi e2e harness exists: `frontend/e2e/word-auto-validate-multiplayer.spec.ts` defines `startMultiplayerGame(page)` — reusable pattern but lives in that file and is not exported.
- ADR sequence: latest is `0033-frontend-otel-public-ingest.md`. Next free slot is **0034**.
- `vitest-axe` is **not** installed.
- No `pnpm a11y` script; e2e runs via `pnpm e2e`.

## File structure (new + modified)

| Path | Action | Purpose |
|---|---|---|
| `frontend/e2e/lib/axeRun.ts` | **create** | Shared helper: WCAG tag set, severity filter, violation summariser, report formatter. Extracted from existing inline code in `a11y.spec.ts`. |
| `frontend/e2e/lib/multiHelpers.ts` | **create** | Re-exports `startMultiplayerGame` so a11y spec doesn't duplicate it. |
| `frontend/e2e/word-auto-validate-multiplayer.spec.ts` | **modify** | Move `startMultiplayerGame` definition into `lib/multiHelpers.ts`, import it back. |
| `frontend/e2e/a11y.spec.ts` | **modify** | Use shared helpers. Apply severity policy. Add 2.1/2.2 tags. Add not-found and multi-waiting-room scans. |
| `frontend/src/test/a11y.ts` | **create** | Tiny wrapper that registers `vitest-axe`'s `toHaveNoViolations` matcher and exports `expectAxeClean(html)`. |
| `frontend/vitest.setup.ts` | **modify** | Import the new `src/test/a11y.ts` so the matcher is globally available. |
| `frontend/package.json` | **modify** | Add `vitest-axe` to `devDependencies`. Add `"a11y": "playwright test e2e/a11y.spec.ts"` script. |
| `docs/adr/0034-a11y-baseline.md` | **create** | The accessibility baseline ADR. |

## Severity policy (locked from spec §4)

| `impact` | gate |
|---|---|
| `critical`, `serious` | **fail** the test |
| `moderate` | log to console, do not fail |
| `minor`, `null`, `undefined` | ignored |

WCAG tag set: `['wcag2a','wcag2aa','wcag21a','wcag21aa','wcag22aa']`.

---

## Task 1: Extract reusable helpers into `e2e/lib/axeRun.ts`

**Files:**
- Create: `frontend/e2e/lib/axeRun.ts`
- Modify: `frontend/e2e/a11y.spec.ts` (just imports — full refactor in Task 3)

The existing `a11y.spec.ts` has `summarise`, `formatReport`, `targetToString`, and `AxeViolationSummary` defined inline. Move them out so they can be reused by every route scan.

- [ ] **Step 1.1: Create `frontend/e2e/lib/axeRun.ts`**

```ts
/**
 * Shared helpers for the WCAG 2.2 A/AA accessibility e2e suite.
 * See ADR-0034 for the baseline rationale and severity policy.
 *
 * Severity policy (see ADR-0034 §3):
 *   critical, serious  → fail the test
 *   moderate           → log to stdout, do not fail
 *   minor / unknown    → ignored
 */
import AxeBuilder from '@axe-core/playwright';
import { expect, type Page } from '@playwright/test';

export const WCAG_TAGS = [
  'wcag2a',
  'wcag2aa',
  'wcag21a',
  'wcag21aa',
  'wcag22aa',
] as const;

const FAILING_IMPACTS = new Set(['critical', 'serious']);
const LOGGING_IMPACTS = new Set(['moderate']);

export interface AxeViolationSummary {
  readonly id: string;
  readonly impact: string | null | undefined;
  readonly description: string;
  readonly help: string;
  readonly helpUrl: string;
  readonly nodeCount: number;
  readonly sampleTargets: readonly string[];
}

function targetToString(target: unknown): string {
  if (typeof target === 'string') return target;
  if (Array.isArray(target)) return target.map(targetToString).join(' ');
  if (target && typeof target === 'object' && 'selector' in target) {
    return targetToString((target as { selector: unknown }).selector);
  }
  return String(target);
}

interface RawViolation {
  readonly id: string;
  readonly impact?: string | null;
  readonly description: string;
  readonly help: string;
  readonly helpUrl: string;
  readonly nodes: ReadonlyArray<{ readonly target: unknown }>;
}

export function summarise(violations: readonly RawViolation[]): AxeViolationSummary[] {
  return violations.map((v) => ({
    id: v.id,
    impact: v.impact,
    description: v.description,
    help: v.help,
    helpUrl: v.helpUrl,
    nodeCount: v.nodes.length,
    sampleTargets: v.nodes.slice(0, 3).map((n) => targetToString(n.target)),
  }));
}

export function formatReport(summary: readonly AxeViolationSummary[]): string {
  return summary
    .map((s, i) =>
      `${i + 1}. [${s.impact ?? 'unknown'}] ${s.id} — ${s.help}\n`
      + `   ${s.nodeCount} node(s); ${s.helpUrl}\n`
      + `   sample selectors:\n`
      + s.sampleTargets.map((t) => `     - ${t}`).join('\n'),
    )
    .join('\n\n');
}

/**
 * Runs axe against the current page state and applies the severity
 * policy. Use after the page has reached the state being audited (DOM
 * settled, fonts loaded, animations idle).
 */
export async function runAxe(page: Page, label: string): Promise<void> {
  const results = await new AxeBuilder({ page })
    .withTags(WCAG_TAGS as unknown as string[])
    .analyze();

  const summary = summarise(results.violations);
  const failing = summary.filter((s) => FAILING_IMPACTS.has(s.impact ?? ''));
  const logging = summary.filter((s) => LOGGING_IMPACTS.has(s.impact ?? ''));

  if (logging.length > 0) {
    // eslint-disable-next-line no-console
    console.log(
      `\n=== ${label}: ${logging.length} moderate a11y finding(s) (logged, not failing) ===\n`
      + `${formatReport(logging)}\n`,
    );
  }

  if (failing.length > 0) {
    // eslint-disable-next-line no-console
    console.log(
      `\n=== ${label}: ${failing.length} blocking a11y violation(s) ===\n`
      + `${formatReport(failing)}\n`,
    );
  }
  expect(
    failing,
    `${label}: blocking WCAG 2.2 A/AA violations (impact ∈ {critical, serious})`,
  ).toEqual([]);
}
```

- [ ] **Step 1.2: Verify the new file typechecks**

Run: `cd frontend && pnpm typecheck`
Expected: PASS (no new errors introduced; `axeRun.ts` is unused but valid).

- [ ] **Step 1.3: Commit**

```bash
git add frontend/e2e/lib/axeRun.ts
git commit -s -m "chore(a11y): extract axe runner helpers into e2e/lib"
```

---

## Task 2: Apply severity policy to existing home-route scan

**Files:**
- Modify: `frontend/e2e/a11y.spec.ts`

Refactor the existing test to use `runAxe`. This *also* expands the tag set to WCAG 2.1 + 2.2 — which may surface new violations. The severity policy ensures only `serious`+ fails; `moderate` is logged.

- [ ] **Step 2.1: Replace the existing a11y.spec.ts content**

Full new file:

```ts
/**
 * WCAG 2.2 A + AA accessibility scan.
 *
 * Runs axe-core (via `@axe-core/playwright`) against representative
 * routes at one desktop viewport. Severity policy and tag set live in
 * `lib/axeRun.ts` — see ADR-0034 for the rationale.
 *
 * Why one viewport (desktop) per route:
 *   - colour-contrast violations are viewport-independent (axe reads
 *     computed styles, not painted pixels);
 *   - structural / aria-role / landmark issues are viewport-independent;
 *   - the few viewport-sensitive a11y rules
 *     (`scrollable-region-focusable`) won't change between 360 px and
 *     1440 px in this app's layout.
 *
 * Complements `clue-overflow.spec.ts` (visual layout) and
 * `clue-ratio.spec.ts` (font-size lower bound).
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

import { test } from '@playwright/test';

import { runAxe } from './lib/axeRun';
import { startMultiplayerGame } from './lib/multiHelpers';

// Same fixture MSW serves in dev/preview — see
// `frontend/src/infrastructure/mocks/handlers.ts`. Keeps the scanned
// page representative of what a reviewer / production user sees.
const STRESS_FIXTURE_PATH = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  '..', 'src', 'infrastructure', 'mocks', 'fixtures', 'puzzle.json',
);
const STRESS_FIXTURE = JSON.parse(
  readFileSync(STRESS_FIXTURE_PATH, 'utf-8'),
) as Record<string, unknown>;

test.describe('WCAG 2.2 A + AA accessibility', () => {
  test('home route (puzzle grid)', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.route(/\/v1\/puzzles\//, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(STRESS_FIXTURE),
      });
    });
    await page.goto('/');
    await page.waitForSelector('[role="grid"]', { state: 'visible' });
    await page.evaluate(() => document.fonts.ready);
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => r(null))));

    await runAxe(page, 'home');
  });

  test('not-found route', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/this-route-does-not-exist');
    // TanStack Router renders the not-found component synchronously;
    // wait for fonts so contrast measurement reads final glyphs.
    await page.evaluate(() => document.fonts.ready);

    await runAxe(page, 'not-found');
  });

  test('multi waiting room', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await startMultiplayerGame(page, { stopBeforeStart: true });
    await page.evaluate(() => document.fonts.ready);

    await runAxe(page, 'multi-waiting-room');
  });
});
```

- [ ] **Step 2.2: Run only the home test, confirm severity policy works**

The new helpers reference `./lib/multiHelpers` which doesn't exist yet — but the home test itself doesn't call it (only the import resolution matters). Let's run the *home* test alone first; if the import fails because of the missing module, address in Task 3.

Run: `cd frontend && pnpm e2e --project=chromium e2e/a11y.spec.ts -g "home"`
Expected: **PASS** (home was clean before; severity-filtered policy is a strict-superset relaxation; if 2.1/2.2 surface new `moderate` issues, they log but don't fail; if they surface `serious`+, this fails — see "Decision branch" below).

If the test fails on `serious`+ violations:
- Stop and read the printed report.
- For each violation: either fix inline (small CSS / aria-label tweak), or document in the ADR as a known issue and add a `disableRules([...])` call in `runAxe`'s caller for that rule with a tight scope.
- Do not relax the severity policy or remove the tag.

- [ ] **Step 2.3: Commit (only if Step 2.2 passes)**

```bash
git add frontend/e2e/a11y.spec.ts
git commit -s -m "feat(a11y): apply severity policy + wcag 2.1/2.2 tags to home scan"
```

---

## Task 3: Extract `startMultiplayerGame` and add waiting-room scan

**Files:**
- Create: `frontend/e2e/lib/multiHelpers.ts`
- Modify: `frontend/e2e/word-auto-validate-multiplayer.spec.ts`

Pull the helper out so the a11y spec can use it without duplicating ~30 lines of WS dance. Also add a `stopBeforeStart` option to scan the waiting room *before* clicking "Démarrer la partie".

- [ ] **Step 3.1: Create `frontend/e2e/lib/multiHelpers.ts`**

Full file contents (this is verbatim the same flow as the existing helper in `word-auto-validate-multiplayer.spec.ts`, with one new `stopBeforeStart` branch added):

```ts
import { expect, type Page } from '@playwright/test';

export interface StartMultiplayerOptions {
  /** Stop after the WaitingRoom is hydrated; do not click "Démarrer". */
  readonly stopBeforeStart?: boolean;
}

/**
 * Drive the home → create-lobby → waiting-room → game flow against the
 * MSW WebSocket mock. Used by both the multiplayer e2e and the a11y
 * scan of the waiting room.
 */
export async function startMultiplayerGame(
  page: Page,
  options: StartMultiplayerOptions = {},
): Promise<void> {
  await page.goto('/');
  await page.getByRole('button', { name: /Créer une partie multijoueur/i }).click();
  await page.waitForURL(/\/lobby\/[^/]+$/);

  const startBtn = page.getByRole('button', { name: /Démarrer la partie/i });
  await expect(startBtn).toBeEnabled({ timeout: 10_000 });
  await expect(page.getByTestId('connection-banner')).toHaveCount(0);

  if (options.stopBeforeStart) return;

  await startBtn.click();
  await page.waitForSelector('[role="grid"]', { state: 'visible', timeout: 10_000 });
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => r(null))));
}
```

- [ ] **Step 3.2: Update `word-auto-validate-multiplayer.spec.ts` to import the helper**

Find this block at the top of the file:

```ts
async function startMultiplayerGame(page: Page): Promise<void> {
  // ... existing body ...
}
```

Replace with:

```ts
import { startMultiplayerGame } from './lib/multiHelpers';
```

(Remove the local function definition entirely. The `Page` type import may still be needed for other helpers in the file — leave the existing `import { expect, test, type Locator, type Page } from '@playwright/test';` line untouched.)

- [ ] **Step 3.3: Run the multiplayer e2e — verify the refactor doesn't break it**

Run: `cd frontend && pnpm e2e --project=chromium e2e/word-auto-validate-multiplayer.spec.ts`
Expected: **PASS** (same behaviour, just imported from a different file).

- [ ] **Step 3.4: Run the a11y waiting-room test**

Run: `cd frontend && pnpm e2e --project=chromium e2e/a11y.spec.ts -g "multi waiting room"`
Expected: **PASS** (or fail with a printed `serious`+ report — same decision branch as Task 2.2).

- [ ] **Step 3.5: Run the a11y not-found test**

Run: `cd frontend && pnpm e2e --project=chromium e2e/a11y.spec.ts -g "not-found"`
Expected: **PASS** (the not-found component is small, almost certainly clean).

- [ ] **Step 3.6: Run the full a11y spec**

Run: `cd frontend && pnpm e2e --project=chromium e2e/a11y.spec.ts`
Expected: 3 tests PASS.

- [ ] **Step 3.7: Commit**

```bash
git add frontend/e2e/lib/multiHelpers.ts frontend/e2e/word-auto-validate-multiplayer.spec.ts frontend/e2e/a11y.spec.ts
git commit -s -m "feat(a11y): scan not-found + multi waiting room routes"
```

---

## Task 4: Add the `pnpm a11y` script

**Files:**
- Modify: `frontend/package.json`

- [ ] **Step 4.1: Read the current scripts block**

Look at the existing `scripts` section (lines 7–18 or so). The `e2e` script is `"e2e": "playwright test"`.

- [ ] **Step 4.2: Add the `a11y` script**

Insert one line in the `scripts` object, just after `"e2e"`:

```json
"a11y": "playwright test e2e/a11y.spec.ts",
```

The result should look like:

```json
"e2e": "playwright test",
"a11y": "playwright test e2e/a11y.spec.ts",
"typecheck": "pnpm panda:codegen && tsc -b",
```

- [ ] **Step 4.3: Verify the script runs**

Run: `cd frontend && pnpm a11y --project=chromium`
Expected: 3 tests PASS.

- [ ] **Step 4.4: Commit**

```bash
git add frontend/package.json
git commit -s -m "chore(a11y): add pnpm a11y script for tight feedback loops"
```

---

## Task 5: Install `vitest-axe` and add the component-test helper

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/vitest.setup.ts`
- Create: `frontend/src/test/a11y.ts`

- [ ] **Step 5.1: Install the package**

Run: `cd frontend && pnpm add -D vitest-axe`
Expected: package added to `devDependencies`, `pnpm-lock.yaml` updated. Verify the new entry pins to a `^x.y.z` version.

- [ ] **Step 5.2: Create `frontend/src/test/a11y.ts`**

```ts
/**
 * Component-level accessibility helpers for vitest. The matcher is
 * registered in `vitest.setup.ts` so any test file can call
 * `expect(html).toHaveNoViolations()` directly. Most tests should
 * prefer `expectAxeClean(html)` for the consistent severity policy
 * (matches the e2e baseline in `e2e/lib/axeRun.ts`).
 *
 * See ADR-0034 §3 for the policy.
 */
import { axe } from 'vitest-axe';
import { expect } from 'vitest';

const FAILING_IMPACTS = new Set(['critical', 'serious']);

/**
 * Runs axe against a rendered DOM node and asserts no critical /
 * serious violations. Moderate findings are logged; minor are ignored.
 */
export async function expectAxeClean(node: Element | Document): Promise<void> {
  const results = await axe(node);
  const violations = results.violations as ReadonlyArray<{
    readonly id: string;
    readonly impact?: string | null;
    readonly help: string;
  }>;
  const failing = violations.filter((v) => FAILING_IMPACTS.has(v.impact ?? ''));
  const logging = violations.filter((v) => v.impact === 'moderate');

  if (logging.length > 0) {
    // eslint-disable-next-line no-console
    console.log(
      `[a11y] ${logging.length} moderate finding(s) (logged):`,
      logging.map((v) => `${v.id}: ${v.help}`).join('; '),
    );
  }

  expect(
    failing.map((v) => ({ id: v.id, impact: v.impact, help: v.help })),
    'component has critical/serious WCAG violations',
  ).toEqual([]);
}
```

- [ ] **Step 5.3: Register the matcher in `vitest.setup.ts`**

Read the current top of `frontend/vitest.setup.ts`. Add this import directly under the existing `import '@testing-library/jest-dom/vitest';` line:

```ts
import { toHaveNoViolations } from 'vitest-axe/matchers';
import { expect } from 'vitest';
expect.extend({ toHaveNoViolations });
```

The top of the file should now read:

```ts
import '@testing-library/jest-dom/vitest';
import { toHaveNoViolations } from 'vitest-axe/matchers';
import { expect } from 'vitest';
expect.extend({ toHaveNoViolations });

// jsdom does not implement window.scrollTo / window.scrollBy. ...
```

(All existing jsdom polyfills below stay unchanged.)

- [ ] **Step 5.4: Run vitest to confirm no regressions**

Run: `cd frontend && pnpm test`
Expected: all existing tests PASS. The new helper is unused; we just confirm the setup file still loads cleanly.

- [ ] **Step 5.5: Typecheck**

Run: `cd frontend && pnpm typecheck`
Expected: PASS.

- [ ] **Step 5.6: Commit**

```bash
git add frontend/package.json frontend/pnpm-lock.yaml frontend/vitest.setup.ts frontend/src/test/a11y.ts
git commit -s -m "feat(a11y): add vitest-axe matcher + expectAxeClean helper"
```

---

## Task 6: ADR-0034 — accessibility baseline

**Files:**
- Create: `docs/adr/0034-a11y-baseline.md`

- [ ] **Step 6.1: Write the ADR**

```markdown
# ADR-0034: Accessibility baseline (WCAG 2.2 AA + screen-reader playability)

## Status

Accepted

## Context

The manifesto requires WCAG AA accessibility as a non-negotiable
baseline ("Accessibility is a requirement (WCAG AA minimum), not a
follow-up ticket"). Prior to this ADR the codebase had:

- one e2e a11y test (`frontend/e2e/a11y.spec.ts`) covering the home
  route only, with strict zero-violation enforcement on WCAG 2.0 A/AA;
- no component-level a11y assertion;
- no severity policy — any violation, regardless of impact, failed CI;
- no documented target screen reader, target locale, or roadmap for
  the screen-reader-playable mots-fléchés grid.

The mots-fléchés grid is the hardest a11y surface in the product (2D
navigation, French clues that arrive on bent arrow cells, validation
events, multiplayer presence). A baseline that doesn't address SR
playability is compliance theatre.

## Decision

### 1. Audit tooling

- **`@axe-core/playwright`** runs against representative routes in the
  existing Playwright e2e harness. Reuses the existing browser; no new
  CI job. Routes scanned: home (puzzle grid), not-found, multi waiting
  room. More routes (solo error state, end-game modal) added as
  follow-up PRs.
- **`vitest-axe`** matcher available to component tests via
  `frontend/src/test/a11y.ts`. Used as components get fixed in
  follow-up work.

Rejected: Lighthouse CI (overlaps axe), pa11y (axe-based, redundant),
`eslint-plugin-jsx-a11y` (false-positive heavy on ark-ui's render-prop
pattern).

### 2. WCAG tag set

`['wcag2a','wcag2aa','wcag21a','wcag21aa','wcag22aa']`

WCAG 2.2 AA is the highest stable tier; including 2.1 explicitly is
defensive against future axe-core defaults.

### 3. Severity policy

| `impact` | gate |
|---|---|
| `critical`, `serious` | **fail** the test |
| `moderate` | log to stdout, do not fail |
| `minor`, `null`, `undefined` | ignored |

Rationale: starting strict on `serious`+ avoids drift; `moderate` is
loose initially so the policy lands without bundling fixes (those
arrive in PR-A2). This ADR commits to **ratcheting `moderate` to fail
within 60 days** of this ADR's merge — reviewed at the next quarterly
manifesto check.

### 4. Disabled rules

None at the time of this ADR. If a rule must be disabled, the ADR is
amended with the rule id, the scope (CSS selector or component path),
and the justification.

### 5. Target screen reader and locale

- **Canonical SR**: VoiceOver on macOS (matches the development
  environment).
- **Primary locale**: French.
- iOS VoiceOver and NVDA/JAWS deferred — call out in test reports if
  found broken; not a CI gate.

### 6. Manual VoiceOver checklist

Run on macOS Safari + Chrome with VO active. Pass/fail per step:

1. Cold load on `/`: Tab reaches the skip link first; activating it
   lands focus on the grid main landmark.
2. Tab again enters the first non-validated cell; VO announces
   *« <n>ème lettre : vide »* (or the letter).
3. Arrow-Right moves to the next cell within the same word; VO
   announces only the cell, no clue.
4. Arrow-Down crosses into a new word; VO announces clue + direction +
   length + slot pattern, exactly once.
5. Type a wrong letter, trigger validation; assertive channel
   interrupts with *« erreur dans le mot ... »*.
6. Reveal a hint; polite channel announces *« lettre <X> révélée,
   position <n> »*.
7. Complete a word; polite channel announces *« mot validé : <WORD> »*.
8. Open end-game modal; focus moves inside; Esc closes; focus returns
   to opener.
9. Reload puzzle; focus stays on body; *« Nouvelle grille chargée »*
   announced.
10. Multi room: another browser joins → polite *« <name> a rejoint la
    partie »*. Disconnect → *« <name> s'est déconnectée »*. Other
    player completes a word → *« <name> a complété un mot »*.
11. axe e2e on the same routes returns zero `serious`/`critical`
    violations.

The PR that ships the screen-reader work (PR-B3 in the
`feat/a11y-foundation` epic) attaches the run output to its
description.

### 7. Deferred items

Tracked here so they don't fall off the radar:

- Settings UI for a11y preferences (verbose multiplayer toggle, slot-
  pattern mute, re-announce hotkey). Until shipped, VoiceOver's native
  *VO+Z* (repeat last phrase) is the documented re-announce path.
- iOS VoiceOver and NVDA/JAWS testing.
- Cognitive accessibility (simpler copy, dyslexia font).
- End-game modal route in axe scan.
- Solo error-state route in axe scan.

## Consequences

- Harder to merge SR-breaking changes — CI now gates on `serious`+
  WCAG 2.2 AA violations on three routes.
- The 60-day `moderate` ratchet creates a forcing function for fixing
  `moderate` findings, not letting them accumulate.
- A future settings PR unblocks user-controlled muting of
  multiplayer announcements.
- Any relaxation of this ADR (rule disables, severity downgrades, tag
  removals) requires an amendment PR.
```

- [ ] **Step 6.2: Verify the file is well-formed**

Run: `head -3 docs/adr/0034-a11y-baseline.md` — first line should be `# ADR-0034: Accessibility baseline (WCAG 2.2 AA + screen-reader playability)`.

- [ ] **Step 6.3: Commit**

```bash
git add docs/adr/0034-a11y-baseline.md
git commit -s -m "docs(adr): adr-0034 accessibility baseline"
```

---

## Task 7: Final verification

- [ ] **Step 7.1: Run lint, typecheck, full vitest, full e2e**

```bash
cd frontend && pnpm lint && pnpm typecheck && pnpm test && pnpm e2e --project=chromium
```

Expected: ALL PASS.

- [ ] **Step 7.2: Inspect the diff size**

Run: `git diff --stat main...HEAD` from the repo root.
Expected: under ~400 lines added (excluding `pnpm-lock.yaml`, which is auto-generated and exempt from the size rule). If over, push the IconButton-style sample test or any unused helper to a follow-up PR.

- [ ] **Step 7.3: Verify the commit log**

Run: `git log --oneline main..HEAD`
Expected: 5 commits with conventional, lowercase-first-word subjects:

```
xxxxxxx docs(adr): adr-0034 accessibility baseline
xxxxxxx feat(a11y): add vitest-axe matcher + expectAxeClean helper
xxxxxxx chore(a11y): add pnpm a11y script for tight feedback loops
xxxxxxx feat(a11y): scan not-found + multi waiting room routes
xxxxxxx feat(a11y): apply severity policy + wcag 2.1/2.2 tags to home scan
xxxxxxx chore(a11y): extract axe runner helpers into e2e/lib
```

(Plus the earlier `docs(a11y): design ...` commit from the spec phase.)

- [ ] **Step 7.4: PAUSE for user manual validation**

Per the saved feedback rule: for UI-touching work, the user manually
tests in real Chrome before any push. Even though this PR is
infrastructure-only, the `pnpm a11y` flow is user-facing. Hand off to
the user with the full local-verification output (Step 7.1 results)
and **wait** for go-ahead before push or PR creation.

Suggested hand-off message:

> All local checks green. Ready for your manual sanity pass — try
> `pnpm a11y --project=chromium` and confirm the run looks right. Once
> you're happy, give me the word to push and open the PR.

---

## Decision branches (referenced from earlier tasks)

### If WCAG 2.1/2.2 tag expansion (Task 2) surfaces blocking violations

Each finding falls into one of three buckets:

1. **Genuine bug, easy fix** (missing `aria-label` on an icon button,
   `<button>` without accessible name, redundant `tabindex`). Fix
   inline as part of Task 2's commit. Append to its commit message:
   `+ fix <component>: <one-line summary>`.

2. **Genuine bug, large fix** (e.g., contrast on a brand colour token
   that's used everywhere). Do **not** fix in PR-A1. Add a
   `disableRules(['<rule-id>'])` for that route only, with an inline
   comment `// TODO PR-A2: <rule-id> on <component> — <reason>`, and
   list the deferral in ADR-0034 §7. Open a tracking issue.

3. **Axe false positive** (rare, but real for ark-ui internals). Add
   `disableRules` with a comment citing the issue link, and append to
   ADR-0034 §4 with the justification.

### If Task 3.4 (waiting room scan) fails because the WS mock is flaky

Set `await expect(startBtn).toBeEnabled({ timeout: 15_000 })` (already
the default 10s in the helper — bump if needed). If still flaky, mark
the test `test.skip` with a `// TODO` referencing the multiplayer e2e
flake and address in a follow-up. Do not regress the mock contract.

---

## Out of scope (PR-A1 will not include)

- End-game modal route (needs deliberate game-completion fixture).
- Solo error-state route (needs typed wrong letter + validation
  trigger; nontrivial setup).
- Component-level vitest-axe sample tests (the matcher is wired; tests
  arrive in PR-A2 as components get fixed).
- The 60-day `moderate` ratchet (committed in ADR §3, executed later).
- Any actual a11y *fix* to existing code — that is PR-A2.
