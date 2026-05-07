/**
 * WCAG 2.1 A + AA accessibility scan.
 *
 * Runs axe-core (via `@axe-core/playwright`) against the home route
 * (the puzzle grid) at one representative viewport. Asserts zero
 * violations of the `wcag2a` and `wcag2aa` tag groups — the bar the
 * project commits to in `MANIFESTO.md` (Ethics: "Accessibility is a
 * requirement (WCAG AA minimum), not a follow-up ticket").
 *
 * Lobby route (ConnectionBanner, PlayerList, WaitingRoom, EndGameModal)
 * is not scanned here because it requires mocking a live WebSocket
 * connection; tracked for a follow-up workstream.
 *
 * Why one viewport (desktop) per route, not all four:
 *   - colour-contrast violations are viewport-independent (axe reads
 *     computed styles, not painted pixels);
 *   - structural / aria-role / landmark issues are viewport-
 *     independent;
 *   - the few viewport-sensitive a11y rules (`scrollable-region-
 *     focusable`) won't change between 360 px and 1440 px in this
 *     app's layout.
 *   So one viewport per route is a sufficient gate, four would just
 *   inflate the runtime.
 *
 * This complements `clue-overflow.spec.ts` (visual layout) and
 * `clue-ratio.spec.ts` (font-size lower bound) — together they form
 * the user-facing-quality gate.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

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

interface AxeViolationSummary {
  readonly id: string;
  readonly impact: string | null | undefined;
  readonly description: string;
  readonly help: string;
  readonly helpUrl: string;
  readonly nodeCount: number;
  readonly sampleTargets: readonly string[];
}

// axe's `nodes[].target` is a union of selector shapes (string,
// CrossTreeSelector, ShadowDomSelector). Coerce to a flat string for
// reporting — we only need it readable, not re-queryable.
function targetToString(target: unknown): string {
  if (typeof target === 'string') return target;
  if (Array.isArray(target)) return target.map(targetToString).join(' ');
  if (target && typeof target === 'object' && 'selector' in target) {
    return targetToString((target as { selector: unknown }).selector);
  }
  return String(target);
}

function summarise(violations: ReadonlyArray<{
  readonly id: string;
  readonly impact?: string | null;
  readonly description: string;
  readonly help: string;
  readonly helpUrl: string;
  readonly nodes: ReadonlyArray<{ readonly target: unknown }>;
}>): AxeViolationSummary[] {
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

function formatReport(summary: readonly AxeViolationSummary[]): string {
  return summary
    .map((s, i) =>
      `${i + 1}. [${s.impact ?? 'unknown'}] ${s.id} — ${s.help}\n`
      + `   ${s.nodeCount} node(s); ${s.helpUrl}\n`
      + `   sample selectors:\n`
      + s.sampleTargets.map((t) => `     - ${t}`).join('\n'),
    )
    .join('\n\n');
}

test.describe('WCAG 2.1 A + AA accessibility', () => {
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

    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa'])
      .analyze();

    const summary = summarise(results.violations);
    if (summary.length > 0) {
      console.log(`\n=== home: ${summary.length} a11y violation(s) ===\n${formatReport(summary)}\n`);
    }
    expect(summary, 'home route has no WCAG 2.1 A/AA violations').toEqual([]);
  });
});
