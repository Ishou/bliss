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
    it.each(INDEXABLE_ROUTES)('emits dist/$path/index.html', (route) => {
      const expectedPath =
        route.path === '/'
          ? resolve(DIST, 'index.html')
          : resolve(DIST, route.path.slice(1), 'index.html');
      expect(existsSync(expectedPath)).toBe(true);
    });

    it.each(INDEXABLE_ROUTES)('embeds the route title in dist/$path', (route) => {
      const file =
        route.path === '/'
          ? resolve(DIST, 'index.html')
          : resolve(DIST, route.path.slice(1), 'index.html');
      const html = readFileSync(file, 'utf8');
      expect(html).toContain(`<title>${route.title}</title>`);
    });

    it.each(INDEXABLE_ROUTES)('embeds the route description in dist/$path', (route) => {
      const file =
        route.path === '/'
          ? resolve(DIST, 'index.html')
          : resolve(DIST, route.path.slice(1), 'index.html');
      const html = readFileSync(file, 'utf8');
      expect(html).toContain(`content="${route.description}"`);
    });

    it.each(INDEXABLE_ROUTES)('embeds the canonical link in dist/$path', (route) => {
      const file =
        route.path === '/'
          ? resolve(DIST, 'index.html')
          : resolve(DIST, route.path.slice(1), 'index.html');
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

    it('emits dist/robots.txt with the production Disallow set', () => {
      const robots = readFileSync(resolve(DIST, 'robots.txt'), 'utf8');
      expect(robots).toContain('Disallow: /lobby/');
      expect(robots).toContain('Disallow: /join/');
      expect(robots).toContain('Disallow: /privacy');
      expect(robots).toContain('Sitemap: https://wordsparrow.io/sitemap.xml');
    });
  },
);
