/**
 * Component-level accessibility helpers for vitest. The matcher is
 * registered in `vitest.setup.ts` so any test file can call
 * `expect(html).toHaveNoViolations()` directly. Most tests should
 * prefer `expectAxeClean(html)` for the consistent severity policy
 * (matches the e2e baseline in `e2e/lib/axeRun.ts`).
 *
 * See ADR-0050 §2 for the WCAG tag set and §3 for the severity policy.
 */
import { axe } from 'vitest-axe';
import { expect } from 'vitest';

// Must stay in sync with WCAG_TAGS in e2e/lib/axeRun.ts (ADR-0050 §2).
// Cross-import is avoided because tsconfig.test.json does not include e2e/.
const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] as const;

const FAILING_IMPACTS = new Set(['critical', 'serious']);

/**
 * Runs axe against a rendered DOM node and asserts no critical /
 * serious violations. Moderate findings are logged; minor are ignored.
 */
export async function expectAxeClean(node: Element | Document): Promise<void> {
  const target = node instanceof Document ? node.documentElement : node;
  const results = await axe(target, {
    runOnly: { type: 'tag', values: [...WCAG_TAGS] },
  });
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
