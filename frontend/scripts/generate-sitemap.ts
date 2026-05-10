// Build-time sitemap.xml generator. Run after `vite build` from the
// `build` script in package.json. Reads INDEXABLE_ROUTES from the
// shared manifest so the sitemap can never drift from the routes
// the app actually indexes. See docs/superpowers/specs/2026-05-10-seo-...

import { writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import {
  INDEXABLE_ROUTES,
  SITE_BASE_URL,
  type IndexableRoute,
} from '../src/ui/seo/routeManifest.ts';

export function renderSitemap(
  routes: ReadonlyArray<IndexableRoute>,
  fallbackLastmod: string,
  lastmodForPath?: (path: string) => string,
): string {
  const urls = routes
    .map((r) => {
      const lastmod = lastmodForPath?.(r.path) ?? fallbackLastmod;
      return `  <url>\n    <loc>${SITE_BASE_URL}${r.path}</loc>\n    <lastmod>${lastmod}</lastmod>\n  </url>`;
    })
    .join('\n');
  return `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls}\n</urlset>\n`;
}

// Path mapping: which file's git history represents this route.
// '/' → accueil.tsx, '/grille' → grille.tsx, etc.
const ROUTE_TO_FILE: Record<string, string> = {
  '/': 'frontend/src/ui/routes/accueil.tsx',
  '/grille': 'frontend/src/ui/routes/grille.tsx',
  '/aide': 'frontend/src/ui/routes/aide.tsx',
  '/mentions-legales': 'frontend/src/ui/routes/mentions-legales.tsx',
  '/confidentialite': 'frontend/src/ui/routes/confidentialite.tsx',
};

function gitLastmod(filePath: string): string | null {
  try {
    const out = execFileSync('git', ['log', '-1', '--format=%cI', '--', filePath], {
      encoding: 'utf8',
      cwd: resolve(import.meta.dirname, '../..'),
    }).trim();
    if (!out) return null;
    return out.slice(0, 10); // YYYY-MM-DD
  } catch {
    return null;
  }
}

function todayISODate(): string {
  return new Date().toISOString().slice(0, 10);
}

function main(): void {
  const fallback = todayISODate();
  const xml = renderSitemap(INDEXABLE_ROUTES, fallback, (path) => {
    const file = ROUTE_TO_FILE[path];
    if (!file) return fallback;
    const fromGit = gitLastmod(file);
    if (fromGit === null) {
      console.warn(
        `[sitemap] git history unavailable for ${file}; using build date ${fallback}`,
      );
      return fallback;
    }
    return fromGit;
  });
  const outPath = resolve(import.meta.dirname, '../dist/sitemap.xml');
  writeFileSync(outPath, xml, 'utf8');
  console.warn(`[sitemap] wrote ${outPath} (${INDEXABLE_ROUTES.length} routes)`);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main();
}
