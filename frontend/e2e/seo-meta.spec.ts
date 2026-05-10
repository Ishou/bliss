import { test, expect } from '@playwright/test';
import { INDEXABLE_ROUTES, SITE_BASE_URL } from '../src/ui/seo';

// Verifies that crawlers and link-preview bots that DON'T run JavaScript
// see the right per-route meta. Skipped by default because the standard
// e2e run uses `vite --mode preview` which serves the SPA shell for every
// route, not the prerendered files. Run against a built deploy with:
//
//   pnpm build
//   SEO_E2E_AGAINST_DIST=1 PLAYWRIGHT_BASE_URL=<built-preview-url> \
//     pnpm playwright test e2e/seo-meta.spec.ts
//
// (or point PLAYWRIGHT_BASE_URL at the Cloudflare Pages preview URL).
//
// The unit + integration suites in tests/seo-prerender-output.test.ts
// already cover the same contract against dist/, so this spec is a
// belt-and-suspenders smoke for the actual deploy environment.

test.describe('per-route SEO meta (JS disabled)', () => {
  test.skip(
    !process.env.SEO_E2E_AGAINST_DIST,
    'set SEO_E2E_AGAINST_DIST=1 to run against a built preview',
  );
  test.use({ javaScriptEnabled: false });

  for (const route of INDEXABLE_ROUTES) {
    test(`${route.path} serves correct title + description`, async ({ page }) => {
      const response = await page.goto(route.path, { waitUntil: 'commit' });
      expect(response?.status()).toBe(200);

      const html = await page.content();
      expect(html).toContain(`<title>${route.title}</title>`);
      expect(html).toContain(`content="${route.description}"`);
      expect(html).toContain(`href="${SITE_BASE_URL}${route.path}"`);
      expect(html).toContain(`property="og:title"`);
    });
  }
});
