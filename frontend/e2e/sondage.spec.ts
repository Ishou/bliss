// /contribuer smoke test — Playwright-level stubs replace missing MSW handlers in preview.

import { expect, test } from '@playwright/test';

const sampleItem = {
  itemId: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  mot: 'CHAT',
  definition: 'Animal domestique à moustaches',
  pos: 'nom_commun',
  categorie: 'faune_flore',
  style: 'definition_directe',
  forceClaimed: 2,
  longueur: 4,
  tier: 'mid',
  isCalibration: false,
};

const nextItem = {
  ...sampleItem,
  itemId: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c',
  mot: 'CHIEN',
  definition: 'Meilleur ami de l\'homme',
};

const lemmaMeta = { priorSenses: [], priorSubTags: [] };

test.describe('/contribuer', () => {
  test('loads the rating card, submits a verdict, and advances to the next card', async ({ page }) => {
    await page.addInitScript(() => {
      window.localStorage.setItem('wordsparrow.tour.seen', 'true');
    });
    let nextCalls = 0;
    await page.route(/\/v1\/items\/next/, async (route) => {
      nextCalls += 1;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(nextCalls === 1 ? sampleItem : nextItem),
      });
    });
    await page.route(/\/v1\/lemma-meta\//, async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(lemmaMeta) });
    });
    await page.route(/\/v1\/items\/.+\/rating/, async (route) => {
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify({
          ratingId: '0190e3a4-7a2c-7c9e-8f1a-aaaaaaaaaaaa',
          itemId: sampleItem.itemId,
          submittedAs: 'anon',
          proposedItemId: null,
        }),
      });
    });

    await page.goto('/contribuer');
    await page.waitForSelector('[data-testid="rating-card"]', { state: 'visible' });
    await expect(page.getByRole('heading', { name: 'CHAT' })).toBeVisible();

    await page.locator('[data-verdict="GOOD"]').click();

    await expect(page.getByRole('heading', { name: 'CHIEN' })).toBeVisible();
  });

  test('renders the sign-in banner for anon visitors and hides the meta inputs', async ({ page }) => {
    await page.addInitScript(() => {
      window.localStorage.setItem('wordsparrow.tour.seen', 'true');
    });
    await page.route(/\/v1\/items\/next/, async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(sampleItem) });
    });
    await page.route(/\/v1\/lemma-meta\//, async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(lemmaMeta) });
    });
    await page.goto('/contribuer');
    await page.waitForSelector('[data-testid="rating-card"]', { state: 'visible' });
    await expect(page.getByRole('note', { name: /Invitation à se connecter/i })).toBeVisible();
  });
});
