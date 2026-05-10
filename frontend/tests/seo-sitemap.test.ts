import { describe, it, expect } from 'vitest';
import { renderSitemap } from '../scripts/generate-sitemap';
import { INDEXABLE_ROUTES, SITE_BASE_URL } from '@/ui/seo';

describe('renderSitemap', () => {
  it('emits a valid XML declaration', () => {
    const xml = renderSitemap(INDEXABLE_ROUTES, '2026-05-10');
    expect(xml).toMatch(/^<\?xml version="1\.0" encoding="UTF-8"\?>/);
  });

  it('uses the sitemap 0.9 namespace', () => {
    const xml = renderSitemap(INDEXABLE_ROUTES, '2026-05-10');
    expect(xml).toContain('xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"');
  });

  it('emits one <url> per route', () => {
    const xml = renderSitemap(INDEXABLE_ROUTES, '2026-05-10');
    const matches = xml.match(/<url>/g) ?? [];
    expect(matches.length).toBe(INDEXABLE_ROUTES.length);
  });

  it('emits absolute <loc> URLs against the base URL', () => {
    const xml = renderSitemap(INDEXABLE_ROUTES, '2026-05-10');
    for (const r of INDEXABLE_ROUTES) {
      expect(xml).toContain(`<loc>${SITE_BASE_URL}${r.path}</loc>`);
    }
  });

  it('emits the supplied lastmod for every entry', () => {
    const xml = renderSitemap(INDEXABLE_ROUTES, '2026-05-10');
    const lastmodCount = (xml.match(/<lastmod>2026-05-10<\/lastmod>/g) ?? []).length;
    expect(lastmodCount).toBe(INDEXABLE_ROUTES.length);
  });

  it('omits changefreq and priority (Google ignores them)', () => {
    const xml = renderSitemap(INDEXABLE_ROUTES, '2026-05-10');
    expect(xml).not.toContain('<changefreq>');
    expect(xml).not.toContain('<priority>');
  });

  // Property-style: any subset of the manifest renders correctly.
  it.each([
    [[]],
    [INDEXABLE_ROUTES.slice(0, 1)],
    [INDEXABLE_ROUTES.slice(0, 3)],
    [[...INDEXABLE_ROUTES].reverse()],
  ])('renders correctly for route subset of length %#', (subset) => {
    const xml = renderSitemap(subset, '2026-05-10');
    const matches = xml.match(/<url>/g) ?? [];
    expect(matches.length).toBe(subset.length);
    for (const r of subset) {
      expect(xml).toContain(`<loc>${SITE_BASE_URL}${r.path}</loc>`);
    }
  });

  it('does not include excluded route patterns', () => {
    const xml = renderSitemap(INDEXABLE_ROUTES, '2026-05-10');
    expect(xml).not.toContain('/lobby/');
    expect(xml).not.toContain('/join/');
    expect(xml).not.toContain('/privacy<');
  });

  it('declares the image-sitemap namespace on <urlset>', () => {
    const xml = renderSitemap(INDEXABLE_ROUTES, '2026-05-10');
    expect(xml).toContain(
      'xmlns:image="http://www.google.com/schemas/sitemap-image/1.1"',
    );
  });

  it('emits one <image:image> child per route with the absolute og image URL', () => {
    const xml = renderSitemap(INDEXABLE_ROUTES, '2026-05-10');
    for (const r of INDEXABLE_ROUTES) {
      expect(xml).toContain(
        `<image:image><image:loc>${SITE_BASE_URL}${r.ogImagePath}</image:loc></image:image>`,
      );
    }
  });

  it('emits exactly one <image:image> block per <url>', () => {
    const xml = renderSitemap(INDEXABLE_ROUTES, '2026-05-10');
    const imageMatches = xml.match(/<image:image>/g) ?? [];
    expect(imageMatches.length).toBe(INDEXABLE_ROUTES.length);
    const imageLocMatches = xml.match(/<image:loc>/g) ?? [];
    expect(imageLocMatches.length).toBe(INDEXABLE_ROUTES.length);
  });
});
