// Smoke test for /sondage. Preview mode doesn't carry survey-api MSW
// handlers (the survey is too small to justify a fixture corpus), so
// we route-stub the next-item + rating endpoints at the Playwright
// level — the route renders the same way against a stub or the real
// server because the contract is the OpenAPI spec.

import { expect, test } from '@playwright/test';

const sampleItem = {
  itemId: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  mot: 'CHAT',
  definition: 'Animal domestique à moustaches',
  pos: 'nom_commun',
  categorie: 'animals',
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

test.describe('/sondage', () => {
  test('loads the rating card, submits, and advances to the next card', async ({ page }) => {
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

    await page.goto('/sondage');
    await page.waitForSelector('[data-testid="rating-card"]', { state: 'visible' });
    await expect(page.getByRole('heading', { name: 'CHAT' })).toBeVisible();

    // Select qualité=4 and difficulté=3 via the radiogroup buttons.
    await page.getByRole('radiogroup', { name: 'Qualité' }).getByRole('radio', { name: '4' }).click();
    await page.getByRole('radiogroup', { name: 'Difficulté' }).getByRole('radio', { name: '3' }).click();

    await page.getByRole('button', { name: 'Suivant' }).click();

    await expect(page.getByRole('heading', { name: 'CHIEN' })).toBeVisible();
  });

  test('renders the sign-in banner for anon visitors and hides the correctif slot', async ({ page }) => {
    await page.addInitScript(() => {
      window.localStorage.setItem('wordsparrow.tour.seen', 'true');
    });
    await page.route(/\/v1\/items\/next/, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(sampleItem),
      });
    });
    await page.goto('/sondage');
    await page.waitForSelector('[data-testid="rating-card"]', { state: 'visible' });
    await expect(page.getByRole('note', { name: /Invitation à se connecter/i })).toBeVisible();
    await expect(page.getByLabel(/Définition alternative/i)).toHaveCount(0);
  });
});
