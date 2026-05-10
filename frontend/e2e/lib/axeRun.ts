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
