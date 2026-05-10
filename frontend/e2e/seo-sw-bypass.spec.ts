import { test, expect } from '@playwright/test';

// Verifies /robots.txt and /sitemap.xml are served as static files with
// the right content-type, NOT as the SPA shell. This is the contract
// crawlers depend on. The full "SW also doesn't hijack these paths"
// behavior is exercised only against a production build with the SW
// active (verified manually post-deploy + by the
// navigateFallbackDenylist regex in vite.config.ts).

test.describe('static SEO files served correctly', () => {
  test('GET /robots.txt returns text/plain with the production rules', async ({ request }) => {
    const response = await request.get('/robots.txt');
    expect(response.status()).toBe(200);
    expect(response.headers()['content-type']).toMatch(/^text\/plain/);
    const body = await response.text();
    expect(body).toContain('Disallow: /lobby/');
    expect(body).toContain('Disallow: /join/');
    expect(body).toContain('Disallow: /privacy');
    expect(body).toContain('Sitemap: https://wordsparrow.io/sitemap.xml');
  });

  test('GET /sitemap.xml returns XML when built into public/', async ({ request }) => {
    // sitemap.xml is generated at build time (scripts/seo/build-sitemap.ts)
    // and lives in dist/, not public/. The dev server (vite --mode preview)
    // does not have it on disk and falls through to the SPA shell. This
    // assertion only makes sense against a built deploy, so we skip in dev.
    //
    // The build-time correctness of sitemap.xml (well-formed XML, urlset
    // root, contains every indexable route) is covered by Task 11's
    // post-build SEO check in CI, plus tests/seo-prerender-output.test.ts.
    //
    // To run this assertion against a real preview, set
    // SEO_E2E_AGAINST_DIST=1 + PLAYWRIGHT_BASE_URL=<built-preview-url>.
    test.skip(
      !process.env.SEO_E2E_AGAINST_DIST,
      'sitemap.xml is build-generated; assertion only valid against built dist/',
    );
    const response = await request.get('/sitemap.xml');
    expect(response.status()).toBe(200);
    expect(response.headers()['content-type']).toMatch(/xml/);
    const body = await response.text();
    expect(body).toContain('<urlset');
  });
});
