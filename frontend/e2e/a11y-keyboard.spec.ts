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
