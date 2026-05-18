import { describe, it, expect } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { INDEXABLE_ROUTES, SITE_BASE_URL } from '@/ui/seo';

const DIST = resolve(__dirname, '../dist');

// This suite asserts the *post-build* state of dist/. It MUST be run
// after `pnpm build`. CI runs it via `pnpm test:post-build` (added in
// Task 11). Running locally without a prior build will fail.
describe.skipIf(!existsSync(resolve(DIST, 'index.html')))(
  'prerender output (post-build)',
  () => {
    it.each(INDEXABLE_ROUTES)('emits dist/$path.html (or dist/index.html for /)', (route) => {
      const expectedPath =
        route.path === '/'
          ? resolve(DIST, 'index.html')
          : resolve(DIST, `${route.path.slice(1)}.html`);
      expect(existsSync(expectedPath)).toBe(true);
    });

    it.each(INDEXABLE_ROUTES)('embeds the route title in dist/$path', (route) => {
      const file =
        route.path === '/'
          ? resolve(DIST, 'index.html')
          : resolve(DIST, `${route.path.slice(1)}.html`);
      const html = readFileSync(file, 'utf8');
      expect(html).toContain(`<title>${route.title}</title>`);
    });

    it.each(INDEXABLE_ROUTES)('embeds the route description in dist/$path', (route) => {
      const file =
        route.path === '/'
          ? resolve(DIST, 'index.html')
          : resolve(DIST, `${route.path.slice(1)}.html`);
      const html = readFileSync(file, 'utf8');
      expect(html).toContain(`content="${route.description}"`);
    });

    it.each(INDEXABLE_ROUTES)('embeds the canonical link in dist/$path', (route) => {
      const file =
        route.path === '/'
          ? resolve(DIST, 'index.html')
          : resolve(DIST, `${route.path.slice(1)}.html`);
      const html = readFileSync(file, 'utf8');
      expect(html).toContain(`href="${SITE_BASE_URL}${route.path}"`);
    });

    it('emits dist/sitemap.xml referencing every indexable route', () => {
      const xml = readFileSync(resolve(DIST, 'sitemap.xml'), 'utf8');
      for (const r of INDEXABLE_ROUTES) {
        expect(xml).toContain(`<loc>${SITE_BASE_URL}${r.path}</loc>`);
      }
    });

    it('does NOT include excluded routes in sitemap.xml', () => {
      const xml = readFileSync(resolve(DIST, 'sitemap.xml'), 'utf8');
      expect(xml).not.toContain('/lobby/');
      expect(xml).not.toContain('/join/');
      expect(xml).not.toContain('/privacy<');
    });

    it('sitemap.xml declares the image namespace', () => {
      const xml = readFileSync(resolve(DIST, 'sitemap.xml'), 'utf8');
      expect(xml).toContain(
        'xmlns:image="http://www.google.com/schemas/sitemap-image/1.1"',
      );
    });

    it.each(INDEXABLE_ROUTES)(
      'sitemap.xml carries image entry for $path',
      (route) => {
        const xml = readFileSync(resolve(DIST, 'sitemap.xml'), 'utf8');
        expect(xml).toContain(
          `<image:loc>${SITE_BASE_URL}${route.ogImagePath}</image:loc>`,
        );
      },
    );

    it('emits dist/robots.txt with the production Disallow set', () => {
      const robots = readFileSync(resolve(DIST, 'robots.txt'), 'utf8');
      expect(robots).toContain('Disallow: /lobby/');
      expect(robots).toContain('Disallow: /join/');
      expect(robots).toContain('Disallow: /privacy');
      expect(robots).toContain('Sitemap: https://wordsparrow.io/sitemap.xml');
    });

    it('embeds JSON-LD WebApplication on the homepage', () => {
      const html = readFileSync(resolve(DIST, 'index.html'), 'utf8');
      expect(html).toContain('"@type":"WebApplication"');
      expect(html).toContain('"applicationCategory":"GameApplication"');
      expect(html).toContain('"inLanguage":"fr"');
    });

    it('embeds Organization JSON-LD on the homepage', () => {
      const html = readFileSync(resolve(DIST, 'index.html'), 'utf8');
      expect(html).toContain('"@type":"Organization"');
      expect(html).toContain('"name":"WordSparrow"');
      expect(html).toContain('"logo":"https://wordsparrow.io/icon-512.png"');
    });

    it('does NOT embed Organization JSON-LD on non-homepage routes', () => {
      for (const path of ['grille', 'aide', 'mentions-legales', 'confidentialite']) {
        const html = readFileSync(resolve(DIST, path, 'index.html'), 'utf8');
        expect(html).not.toContain('"@type":"Organization"');
      }
    });

    it('embeds a FAQPage with one Question per HELP_SECTIONS entry on /aide', () => {
      const html = readFileSync(resolve(DIST, 'aide', 'index.html'), 'utf8');
      expect(html).toContain('"@type":"FAQPage"');
      // 5 HELP_SECTIONS entries → exactly 5 Question objects.
      const questionMatches = html.match(/"@type":"Question"/g) ?? [];
      expect(questionMatches).toHaveLength(5);
    });

    it.each([
      ['aide', '/aide'],
      ['grille', '/grille'],
      ['mentions-legales', '/mentions-legales'],
      ['confidentialite', '/confidentialite'],
    ])('embeds BreadcrumbList JSON-LD on /%s', (dir) => {
      const html = readFileSync(resolve(DIST, dir, 'index.html'), 'utf8');
      expect(html).toContain('"@type":"BreadcrumbList"');
    });

    it('does NOT embed BreadcrumbList on the homepage (it is the root, not a child)', () => {
      const html = readFileSync(resolve(DIST, 'index.html'), 'utf8');
      expect(html).not.toContain('"@type":"BreadcrumbList"');
    });

    it('embeds Game JSON-LD on /grille', () => {
      const html = readFileSync(resolve(DIST, 'grille', 'index.html'), 'utf8');
      expect(html).toContain('"@type":"Game"');
      expect(html).toContain('"genre":"Word puzzle"');
      expect(html).toContain('"gamePlatform":"Web browser"');
    });

    it('does NOT embed Game JSON-LD on the homepage', () => {
      const html = readFileSync(resolve(DIST, 'index.html'), 'utf8');
      expect(html).not.toContain('"@type":"Game"');
    });

    it.each(INDEXABLE_ROUTES)('emits exactly one <title> in dist/$path', (route) => {
      const file =
        route.path === '/'
          ? resolve(DIST, 'index.html')
          : resolve(DIST, `${route.path.slice(1)}.html`);
      const html = readFileSync(file, 'utf8');
      const titleCount = (html.match(/<title>/g) ?? []).length;
      expect(titleCount).toBe(1);
    });

    it.each(INDEXABLE_ROUTES)('emits exactly one og:image in dist/$path', (route) => {
      const file =
        route.path === '/'
          ? resolve(DIST, 'index.html')
          : resolve(DIST, `${route.path.slice(1)}.html`);
      const html = readFileSync(file, 'utf8');
      const ogImageCount = (html.match(/property="og:image"/g) ?? []).length;
      expect(ogImageCount).toBe(1);
    });

    it.each(INDEXABLE_ROUTES)('emits exactly one canonical link in dist/$path', (route) => {
      const file =
        route.path === '/'
          ? resolve(DIST, 'index.html')
          : resolve(DIST, `${route.path.slice(1)}.html`);
      const html = readFileSync(file, 'utf8');
      const canonicalCount = (html.match(/rel="canonical"/g) ?? []).length;
      expect(canonicalCount).toBe(1);
    });

    it.each(INDEXABLE_ROUTES)(
      'embeds the per-route og:image in dist/$path',
      (route) => {
        const file =
          route.path === '/'
            ? resolve(DIST, 'index.html')
            : resolve(DIST, `${route.path.slice(1)}.html`);
        const html = readFileSync(file, 'utf8');
        const expected = `${SITE_BASE_URL}${route.ogImagePath}`;
        expect(html).toContain(`property="og:image" content="${expected}"`);
        expect(html).toContain(`name="twitter:image" content="${expected}"`);
        // The shared default must NOT leak into indexable routes.
        expect(html).not.toContain('og-default.png');
      },
    );

    // H1 keyword targeting (sub-project #3 phase 1). Each indexable
    // user-facing route must contain at least one H1 with French target
    // keywords. Compliance routes (mentions-legales, confidentialite)
    // keep their existing legal-page headings — not targets for SEO.
    it('homepage H1 carries the "mots fléchés français" target phrase', () => {
      const html = readFileSync(resolve(DIST, 'index.html'), 'utf8');
      expect(html).toMatch(
        /<h1\b[^>]*lang="fr"[^>]*>[\s\S]*?Mots fléchés français en ligne[\s\S]*?<\/h1>/,
      );
    });

    it('grille H1 carries the "grille de mots fléchés du jour" target phrase', () => {
      const html = readFileSync(resolve(DIST, 'grille', 'index.html'), 'utf8');
      expect(html).toMatch(
        /<h1\b[^>]*lang="fr"[^>]*>[\s\S]*?Grille de mots fléchés du jour[\s\S]*?<\/h1>/,
      );
    });

    it('aide H1 carries the "comment jouer aux mots fléchés en ligne" target phrase', () => {
      const html = readFileSync(resolve(DIST, 'aide', 'index.html'), 'utf8');
      expect(html).toMatch(
        /<h1\b[^>]*>[^<]*Comment jouer aux mots fléchés en ligne[^<]*<\/h1>/,
      );
    });

    // Guard against the "frozen tour on hard refresh" bug. The
    // prerender browser starts with empty localStorage; without the
    // `wordsparrow.tour.seen` seed in `scripts/prerender.ts`,
    // `useSoloTour` auto-opens the welcome step and the Portal-rendered
    // tour parts get baked into `dist/grille/index.html` as
    // `data-state="open"` markup. Real visitors then load that static
    // HTML and see a tour with no React handlers attached (Portal
    // content is outside the route's hydratable subtree, so the
    // client-side machine never adopts it).
    //
    // Closed-state tour parts (`data-scope="tour"` with `hidden`) still
    // render via the Portal; they are inert and invisible. We only
    // assert the open-state surface is absent.
    it.each(INDEXABLE_ROUTES)(
      'does not bake the open-state solo tour into dist/$path',
      (route) => {
        const file =
          route.path === '/'
            ? resolve(DIST, 'index.html')
            : resolve(DIST, `${route.path.slice(1)}.html`);
        const html = readFileSync(file, 'utf8');
        // Only the open-state portal markup signals the bug. The
        // closed-state Tour.Content (hidden, with the always-rendered
        // dismiss button inside it) is harmless.
        expect(html).not.toMatch(/data-scope="tour"[^>]*data-state="open"/);
        // Welcome-step title only renders into the DOM when the zag
        // machine is active (it's bound through the live step record,
        // not the JSX children).
        expect(html).not.toContain('Bienvenue');
        // `progressText` resolves to "Étape 1 sur N" only when current
        // step is 0 (open at welcome); closed-state shows "Étape 0 sur N".
        expect(html).not.toMatch(/Étape 1 sur \d+/);
      },
    );
  },
);

// File-existence checks for the per-route OG images. These run regardless
// of whether dist/ has been built — they assert the public/ checked-in
// assets, not the post-build output.
describe('per-route OG image assets', () => {
  it.each(INDEXABLE_ROUTES)(
    'public/<og image> exists for $path',
    (route) => {
      const path = resolve(
        __dirname,
        '..',
        'public',
        route.ogImagePath.slice(1),
      );
      expect(existsSync(path)).toBe(true);
    },
  );
});
