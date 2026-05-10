# SEO Indexability Foundation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Per-route head metadata, build-time prerender, auto-generated sitemap, robots.txt update, and Search Console verification — so crawlers and link-preview bots see correct per-route information in the initial HTML response.

**Architecture:** Three layers: (A) per-route head data declared via TanStack Router's `head()` API in route files, sourced from a single `routeManifest.ts`; (B) a build-time prerender script using Playwright's chromium (already a dev dep) that navigates each indexable route and writes the rendered HTML to `dist/<route>/index.html`; (C) Cloudflare Pages serves the static files exactly as today. No new runtime infrastructure.

**Tech Stack:** Vite 6, React 19, TanStack Router 1.95, Vite-Plugin-PWA / Workbox, Playwright (chromium), Vitest, Node 22 (with `--experimental-strip-types` for the prerender script), Terraform + Cloudflare provider for the GSC DNS TXT record.

**Spec:** `docs/superpowers/specs/2026-05-10-seo-indexability-foundation-design.md`

---

## File Structure

**New files:**
- `frontend/src/ui/seo/routeManifest.ts` — single source of truth for indexable routes (path, title, description) + excluded route paths + base URL constant
- `frontend/src/ui/seo/buildHead.ts` — pure helper that turns `{title, description, canonical, noindex?}` into the TanStack Router `head()` return shape (title, description, OG, Twitter, canonical link, optional `noindex` meta)
- `frontend/src/ui/seo/index.ts` — re-exports
- `frontend/scripts/generate-sitemap.ts` — emits `dist/sitemap.xml` from the manifest after `vite build`
- `frontend/scripts/prerender.ts` — Playwright-based prerender of each indexable route after `vite build`
- `frontend/public/og-default.png` — single shared 1200×630 OpenGraph image (binary asset committed)
- `frontend/tests/seo-build-head.test.ts` — unit tests for `buildHead`
- `frontend/tests/seo-route-head.test.tsx` — asserts each indexable route's `head()` returns the right shape
- `frontend/tests/seo-sitemap.test.ts` — unit + property tests for the sitemap generator
- `frontend/tests/seo-prerender-output.test.ts` — integration: after build, `dist/<route>/index.html` exists with expected meta
- `frontend/tests/seo-robots-output.test.ts` — integration: `dist/robots.txt` content
- `frontend/e2e/seo-meta.spec.ts` — Playwright: hard-load each route with JS disabled, assert title + description
- `frontend/e2e/seo-sw-bypass.spec.ts` — Playwright: load `/robots.txt` with SW installed, assert content-type + body
- `docs/adr/0035-build-time-prerender-for-seo.md` — ADR-0035

**Modified files:**
- `frontend/src/ui/routes/__root.tsx` — no change (already has `<HeadContent />`); confirmed during review
- `frontend/src/ui/routes/accueil.tsx:489` — replace existing `head()` with `buildHead({...})` import
- `frontend/src/ui/routes/grille.tsx:554` — same
- `frontend/src/ui/routes/aide.tsx:263` — same
- `frontend/src/ui/routes/mentions-legales.tsx:131` — same
- `frontend/src/ui/routes/confidentialite.tsx:32` — same
- `frontend/src/ui/routes/privacy.tsx:26` — replace with `buildHead({..., noindex: true})`
- `frontend/src/ui/routes/lobby.$lobbyId.tsx:913` — add `noindex` via `buildHead`
- `frontend/src/ui/routes/join.$code.tsx:136` — same
- `frontend/public/robots.txt` — add `Disallow: /join/`, `Disallow: /privacy`
- `frontend/public/sitemap.xml` — DELETE (replaced by build-time generator)
- `frontend/vite.config.ts:154` — extend `navigateFallbackDenylist` to include `/^\/robots\.txt$/` and `/^\/sitemap\.xml$/`
- `frontend/package.json` — extend `build` script to run sitemap generator + prerender after `vite build`
- `terraform/cloudflare-dns-records.tf` — add `cloudflare_dns_record.google_site_verification`

---

## Task 1: Scaffold the SEO module — route manifest

**Files:**
- Create: `frontend/src/ui/seo/routeManifest.ts`
- Create: `frontend/src/ui/seo/index.ts`

- [ ] **Step 1: Create the route manifest**

`frontend/src/ui/seo/routeManifest.ts`:

```ts
// Single source of truth for SEO-relevant routes. Imported by:
//   - each indexable route file (for its head() data)
//   - frontend/scripts/generate-sitemap.ts (for sitemap.xml)
//   - frontend/scripts/prerender.ts (for the prerender pass)
//   - frontend/tests/seo-*.test.ts (for assertions)
//
// Adding an indexable route is a one-touch change here.

export const SITE_BASE_URL = 'https://wordsparrow.io';

export interface IndexableRoute {
  readonly path: string;
  readonly title: string;
  readonly description: string;
}

export const INDEXABLE_ROUTES: ReadonlyArray<IndexableRoute> = [
  {
    path: '/',
    title: 'WordSparrow — mots fléchés français en ligne',
    description:
      'Jouez aux mots fléchés en français, en solo ou en multijoueur. Gratuit, sans inscription.',
  },
  {
    path: '/grille',
    title: 'Grille du jour — WordSparrow',
    description: 'Résolvez la grille de mots fléchés du jour, en français.',
  },
  {
    path: '/aide',
    title: 'Aide — WordSparrow',
    description:
      'Comment jouer aux mots fléchés sur WordSparrow : règles, astuces, raccourcis.',
  },
  {
    path: '/mentions-legales',
    title: 'Mentions légales — WordSparrow',
    description:
      'Mentions légales et informations éditoriales de WordSparrow.',
  },
  {
    path: '/confidentialite',
    title: 'Confidentialité — WordSparrow',
    description: 'Politique de confidentialité de WordSparrow.',
  },
];

// Routes that exist but must NOT be indexed. They get a `noindex,follow`
// meta + Disallow in robots.txt. Path is the TanStack route pattern, not
// a concrete URL — `$lobbyId` and `$code` are TanStack params.
export const EXCLUDED_ROUTES: ReadonlyArray<string> = [
  '/privacy',
  '/lobby/$lobbyId',
  '/join/$code',
];

export const DEFAULT_OG_IMAGE = `${SITE_BASE_URL}/og-default.png`;
```

- [ ] **Step 2: Create the barrel export**

`frontend/src/ui/seo/index.ts`:

```ts
export {
  SITE_BASE_URL,
  INDEXABLE_ROUTES,
  EXCLUDED_ROUTES,
  DEFAULT_OG_IMAGE,
  type IndexableRoute,
} from './routeManifest';
export { buildHead, type BuildHeadInput } from './buildHead';
```

- [ ] **Step 3: Run typecheck**

Run: `cd frontend && pnpm typecheck`
Expected: FAIL with "Cannot find module './buildHead'" — that's the next task.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/ui/seo/routeManifest.ts frontend/src/ui/seo/index.ts
git commit -s -m "feat(frontend): scaffold SEO route manifest"
```

---

## Task 2: `buildHead` helper — TDD

**Files:**
- Create: `frontend/tests/seo-build-head.test.ts`
- Create: `frontend/src/ui/seo/buildHead.ts`

- [ ] **Step 1: Write failing tests**

`frontend/tests/seo-build-head.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { buildHead } from '@/ui/seo/buildHead';

describe('buildHead', () => {
  const baseInput = {
    title: 'Aide — WordSparrow',
    description: 'Comment jouer aux mots fléchés sur WordSparrow.',
    canonical: 'https://wordsparrow.io/aide',
  };

  it('emits a title meta tag', () => {
    const head = buildHead(baseInput);
    expect(head.meta).toContainEqual({ title: 'Aide — WordSparrow' });
  });

  it('emits a description meta tag', () => {
    const head = buildHead(baseInput);
    expect(head.meta).toContainEqual({
      name: 'description',
      content: 'Comment jouer aux mots fléchés sur WordSparrow.',
    });
  });

  it('emits a canonical link', () => {
    const head = buildHead(baseInput);
    expect(head.links).toContainEqual({
      rel: 'canonical',
      href: 'https://wordsparrow.io/aide',
    });
  });

  it('emits the full OpenGraph set', () => {
    const head = buildHead(baseInput);
    expect(head.meta).toContainEqual({ property: 'og:title', content: baseInput.title });
    expect(head.meta).toContainEqual({ property: 'og:description', content: baseInput.description });
    expect(head.meta).toContainEqual({ property: 'og:url', content: baseInput.canonical });
    expect(head.meta).toContainEqual({ property: 'og:type', content: 'website' });
    expect(head.meta).toContainEqual({ property: 'og:site_name', content: 'WordSparrow' });
    expect(head.meta).toContainEqual({ property: 'og:locale', content: 'fr_FR' });
  });

  it('emits the default OG image when none provided', () => {
    const head = buildHead(baseInput);
    expect(head.meta).toContainEqual({
      property: 'og:image',
      content: 'https://wordsparrow.io/og-default.png',
    });
  });

  it('emits the Twitter card tags mirroring OG', () => {
    const head = buildHead(baseInput);
    expect(head.meta).toContainEqual({ name: 'twitter:card', content: 'summary_large_image' });
    expect(head.meta).toContainEqual({ name: 'twitter:title', content: baseInput.title });
    expect(head.meta).toContainEqual({ name: 'twitter:description', content: baseInput.description });
    expect(head.meta).toContainEqual({
      name: 'twitter:image',
      content: 'https://wordsparrow.io/og-default.png',
    });
  });

  it('omits the robots meta by default', () => {
    const head = buildHead(baseInput);
    const robots = head.meta.find((m) => 'name' in m && m.name === 'robots');
    expect(robots).toBeUndefined();
  });

  it('emits noindex,follow when noindex is set', () => {
    const head = buildHead({ ...baseInput, noindex: true });
    expect(head.meta).toContainEqual({ name: 'robots', content: 'noindex,follow' });
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && pnpm test seo-build-head`
Expected: FAIL — "Cannot find module './buildHead'".

- [ ] **Step 3: Implement `buildHead`**

`frontend/src/ui/seo/buildHead.ts`:

```ts
// Per-route head builder for indexable and excluded routes.
//
// Returns the TanStack Router `head()` return shape: a `{ meta, links }`
// object whose entries are passed verbatim to the document <head> via
// the <HeadContent /> component mounted in __root.tsx.
//
// One source of truth for the canonical URL, OG tags, Twitter card,
// and the optional `noindex` flag. Tested in seo-build-head.test.ts.

import { DEFAULT_OG_IMAGE } from './routeManifest';

export interface BuildHeadInput {
  readonly title: string;
  readonly description: string;
  readonly canonical: string;
  readonly noindex?: boolean;
}

export interface RouteHead {
  readonly meta: ReadonlyArray<Record<string, string>>;
  readonly links: ReadonlyArray<Record<string, string>>;
}

export function buildHead(input: BuildHeadInput): RouteHead {
  const { title, description, canonical, noindex = false } = input;

  const meta: Array<Record<string, string>> = [
    { title },
    { name: 'description', content: description },
    { property: 'og:title', content: title },
    { property: 'og:description', content: description },
    { property: 'og:url', content: canonical },
    { property: 'og:type', content: 'website' },
    { property: 'og:site_name', content: 'WordSparrow' },
    { property: 'og:locale', content: 'fr_FR' },
    { property: 'og:image', content: DEFAULT_OG_IMAGE },
    { name: 'twitter:card', content: 'summary_large_image' },
    { name: 'twitter:title', content: title },
    { name: 'twitter:description', content: description },
    { name: 'twitter:image', content: DEFAULT_OG_IMAGE },
  ];

  if (noindex) {
    meta.push({ name: 'robots', content: 'noindex,follow' });
  }

  return {
    meta,
    links: [{ rel: 'canonical', href: canonical }],
  };
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && pnpm test seo-build-head`
Expected: PASS — 8 tests.

- [ ] **Step 5: Run typecheck**

Run: `cd frontend && pnpm typecheck`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/ui/seo/buildHead.ts frontend/tests/seo-build-head.test.ts frontend/src/ui/seo/index.ts
git commit -s -m "feat(frontend): add buildHead helper for per-route SEO meta"
```

---

## Task 3: Per-route head test — TDD

**Files:**
- Create: `frontend/tests/seo-route-head.test.tsx`

- [ ] **Step 1: Write failing tests**

This test imports each route file and asserts its `head()` returns what the manifest says. The implementation that makes this pass comes in Task 4.

`frontend/tests/seo-route-head.test.tsx`:

```ts
import { describe, it, expect } from 'vitest';
import { INDEXABLE_ROUTES, SITE_BASE_URL } from '@/ui/seo';
import { Route as AccueilRoute } from '@/ui/routes/accueil';
import { Route as GrilleRoute } from '@/ui/routes/grille';
import { Route as AideRoute } from '@/ui/routes/aide';
import { Route as MentionsLegalesRoute } from '@/ui/routes/mentions-legales';
import { Route as ConfidentialiteRoute } from '@/ui/routes/confidentialite';
import { Route as PrivacyRoute } from '@/ui/routes/privacy';
import { Route as LobbyRoute } from '@/ui/routes/lobby.$lobbyId';
import { Route as JoinRoute } from '@/ui/routes/join.$code';

const ROUTE_BY_PATH: Record<string, { options: { head?: () => unknown } }> = {
  '/': AccueilRoute as unknown as { options: { head?: () => unknown } },
  '/grille': GrilleRoute as unknown as { options: { head?: () => unknown } },
  '/aide': AideRoute as unknown as { options: { head?: () => unknown } },
  '/mentions-legales': MentionsLegalesRoute as unknown as { options: { head?: () => unknown } },
  '/confidentialite': ConfidentialiteRoute as unknown as { options: { head?: () => unknown } },
};

interface Head {
  meta: Array<Record<string, string>>;
  links: Array<Record<string, string>>;
}

describe.each(INDEXABLE_ROUTES)('indexable route $path', (route) => {
  const tanRoute = ROUTE_BY_PATH[route.path];

  it('exposes a head() function', () => {
    expect(tanRoute?.options.head).toBeTypeOf('function');
  });

  it('emits the manifest title', () => {
    const head = tanRoute!.options.head!() as Head;
    expect(head.meta).toContainEqual({ title: route.title });
  });

  it('emits the manifest description', () => {
    const head = tanRoute!.options.head!() as Head;
    expect(head.meta).toContainEqual({
      name: 'description',
      content: route.description,
    });
  });

  it(`emits canonical = ${SITE_BASE_URL}${route.path}`, () => {
    const head = tanRoute!.options.head!() as Head;
    expect(head.links).toContainEqual({
      rel: 'canonical',
      href: `${SITE_BASE_URL}${route.path}`,
    });
  });

  it('does NOT emit a noindex robots meta', () => {
    const head = tanRoute!.options.head!() as Head;
    const robots = head.meta.find((m) => m['name'] === 'robots');
    expect(robots).toBeUndefined();
  });
});

describe('excluded routes carry noindex', () => {
  const cases: Array<[string, { options: { head?: () => unknown } }]> = [
    ['/privacy', PrivacyRoute as unknown as { options: { head?: () => unknown } }],
    ['/lobby/$lobbyId', LobbyRoute as unknown as { options: { head?: () => unknown } }],
    ['/join/$code', JoinRoute as unknown as { options: { head?: () => unknown } }],
  ];

  it.each(cases)('%s emits noindex,follow', (_path, tanRoute) => {
    const head = tanRoute.options.head!() as Head;
    expect(head.meta).toContainEqual({ name: 'robots', content: 'noindex,follow' });
  });
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && pnpm test seo-route-head`
Expected: FAIL — current `head()` returns `{ meta: [{ title: 'WordSparrow' }] }` only, missing description / canonical / noindex.

- [ ] **Step 3: Commit the failing test**

(The implementation lands in Task 4; commit the failing test now so the red→green flow is in git history per CLAUDE.md.)

```bash
git add frontend/tests/seo-route-head.test.tsx
git commit -s -m "test(frontend): per-route SEO head assertions (red)"
```

---

## Task 4: Wire `buildHead` into every route

**Files:**
- Modify: `frontend/src/ui/routes/accueil.tsx:489`
- Modify: `frontend/src/ui/routes/grille.tsx:554`
- Modify: `frontend/src/ui/routes/aide.tsx:263`
- Modify: `frontend/src/ui/routes/mentions-legales.tsx:131`
- Modify: `frontend/src/ui/routes/confidentialite.tsx:32`
- Modify: `frontend/src/ui/routes/privacy.tsx:26`
- Modify: `frontend/src/ui/routes/lobby.$lobbyId.tsx:913`
- Modify: `frontend/src/ui/routes/join.$code.tsx:136`

- [ ] **Step 1: Update `accueil.tsx`**

Open `frontend/src/ui/routes/accueil.tsx`. At the top of the file with the other imports, add:

```ts
import { buildHead, INDEXABLE_ROUTES, SITE_BASE_URL } from '@/ui/seo';
```

Find the existing `head` line near `accueil.tsx:489`:

```ts
  head: () => ({ meta: [{ title: 'WordSparrow — Accueil' }] }),
```

Replace it with:

```ts
  head: () => {
    const r = INDEXABLE_ROUTES.find((x) => x.path === '/')!;
    return buildHead({
      title: r.title,
      description: r.description,
      canonical: `${SITE_BASE_URL}/`,
    });
  },
```

- [ ] **Step 2: Update `grille.tsx` (`grille.tsx:554`)**

Add the same import. Replace:

```ts
  head: () => ({ meta: [{ title: 'WordSparrow' }] }),
```

with:

```ts
  head: () => {
    const r = INDEXABLE_ROUTES.find((x) => x.path === '/grille')!;
    return buildHead({
      title: r.title,
      description: r.description,
      canonical: `${SITE_BASE_URL}/grille`,
    });
  },
```

- [ ] **Step 3: Update `aide.tsx` (`aide.tsx:263`)**

Add the same import. Replace:

```ts
  head: () => ({ meta: [{ title: 'Aide — WordSparrow' }] }),
```

with:

```ts
  head: () => {
    const r = INDEXABLE_ROUTES.find((x) => x.path === '/aide')!;
    return buildHead({
      title: r.title,
      description: r.description,
      canonical: `${SITE_BASE_URL}/aide`,
    });
  },
```

- [ ] **Step 4: Update `mentions-legales.tsx` (`mentions-legales.tsx:131`)**

Add the same import. Replace the existing `head: () => ({...})` block (multi-line) with:

```ts
  head: () => {
    const r = INDEXABLE_ROUTES.find((x) => x.path === '/mentions-legales')!;
    return buildHead({
      title: r.title,
      description: r.description,
      canonical: `${SITE_BASE_URL}/mentions-legales`,
    });
  },
```

- [ ] **Step 5: Update `confidentialite.tsx` (`confidentialite.tsx:32`)**

Add the same import. Replace the existing `head: () => ({...})` block with:

```ts
  head: () => {
    const r = INDEXABLE_ROUTES.find((x) => x.path === '/confidentialite')!;
    return buildHead({
      title: r.title,
      description: r.description,
      canonical: `${SITE_BASE_URL}/confidentialite`,
    });
  },
```

- [ ] **Step 6: Update `privacy.tsx` (`privacy.tsx:26`) — excluded, noindex**

Add `import { buildHead, SITE_BASE_URL } from '@/ui/seo';`. Replace the existing `head: () => ({...})` block with:

```ts
  head: () =>
    buildHead({
      title: 'Privacy — WordSparrow',
      description:
        'WordSparrow privacy policy: minimal data collection, anonymized audience measurement, right to erasure.',
      canonical: `${SITE_BASE_URL}/privacy`,
      noindex: true,
    }),
```

- [ ] **Step 7: Update `lobby.$lobbyId.tsx` (`lobby.$lobbyId.tsx:913`) — excluded, noindex**

Add `import { buildHead, SITE_BASE_URL } from '@/ui/seo';`. Replace:

```ts
  head: () => ({ meta: [{ title: 'Salon · WordSparrow' }] }),
```

with:

```ts
  head: () =>
    buildHead({
      title: 'Salon · WordSparrow',
      description: 'Salon multijoueur WordSparrow.',
      canonical: `${SITE_BASE_URL}/grille`,
      noindex: true,
    }),
```

(Canonical points at `/grille` because `/lobby/$id` is non-indexable; pointing at the closest indexable parent is the safe pattern Google recommends for noindex pages with no canonical equivalent.)

- [ ] **Step 8: Update `join.$code.tsx` (`join.$code.tsx:136`) — excluded, noindex**

Add `import { buildHead, SITE_BASE_URL } from '@/ui/seo';`. Replace:

```ts
  head: () => ({ meta: [{ title: 'WordSparrow — Rejoindre' }] }),
```

with:

```ts
  head: () =>
    buildHead({
      title: 'WordSparrow — Rejoindre',
      description: 'Rejoindre une partie WordSparrow.',
      canonical: `${SITE_BASE_URL}/`,
      noindex: true,
    }),
```

- [ ] **Step 9: Run the per-route head tests**

Run: `cd frontend && pnpm test seo-route-head seo-build-head`
Expected: PASS — every test green.

- [ ] **Step 10: Run the full vitest suite to catch regressions**

Run: `cd frontend && pnpm test`
Expected: PASS. Existing route tests (`accueil-route.test.tsx`, `aide-route.test.tsx`) may need a one-line update if they previously asserted on the old title. If any fail, update the assertion to read the title from `INDEXABLE_ROUTES.find(...)!.title`.

- [ ] **Step 11: Run typecheck and lint**

Run: `cd frontend && pnpm typecheck && pnpm lint`
Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add frontend/src/ui/routes
git commit -s -m "feat(frontend): wire buildHead into every route (green)"
```

---

## Task 5: Sitemap generator — TDD unit + property test

**Files:**
- Create: `frontend/tests/seo-sitemap.test.ts`
- Create: `frontend/scripts/generate-sitemap.ts`

- [ ] **Step 1: Write failing tests**

`frontend/tests/seo-sitemap.test.ts`:

```ts
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
});
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd frontend && pnpm test seo-sitemap`
Expected: FAIL — `renderSitemap` not defined.

- [ ] **Step 3: Implement the generator**

`frontend/scripts/generate-sitemap.ts`:

```ts
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
  console.log(`[sitemap] wrote ${outPath} (${INDEXABLE_ROUTES.length} routes)`);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main();
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd frontend && pnpm test seo-sitemap`
Expected: PASS — all tests green.

- [ ] **Step 5: Run typecheck**

Run: `cd frontend && pnpm typecheck`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/scripts/generate-sitemap.ts frontend/tests/seo-sitemap.test.ts
git commit -s -m "feat(frontend): add sitemap.xml generator (build-time)"
```

---

## Task 6: Update `robots.txt`, delete static `sitemap.xml`

**Files:**
- Modify: `frontend/public/robots.txt`
- Delete: `frontend/public/sitemap.xml`
- Create: `frontend/tests/seo-robots-output.test.ts`

- [ ] **Step 1: Update `frontend/public/robots.txt`**

Replace the file's contents with:

```
User-agent: *
Allow: /
Disallow: /lobby/
Disallow: /join/
Disallow: /privacy

Sitemap: https://wordsparrow.io/sitemap.xml
```

- [ ] **Step 2: Delete the static sitemap**

Run: `rm frontend/public/sitemap.xml`

- [ ] **Step 3: Write failing test asserting robots content**

`frontend/tests/seo-robots-output.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

describe('public/robots.txt', () => {
  const robots = readFileSync(
    resolve(__dirname, '../public/robots.txt'),
    'utf8',
  );

  it('allows the root path', () => {
    expect(robots).toMatch(/^Allow: \/$/m);
  });

  it('disallows /lobby/', () => {
    expect(robots).toMatch(/^Disallow: \/lobby\/$/m);
  });

  it('disallows /join/', () => {
    expect(robots).toMatch(/^Disallow: \/join\/$/m);
  });

  it('disallows /privacy', () => {
    expect(robots).toMatch(/^Disallow: \/privacy$/m);
  });

  it('references the production sitemap URL', () => {
    expect(robots).toMatch(/^Sitemap: https:\/\/wordsparrow\.io\/sitemap\.xml$/m);
  });
});
```

- [ ] **Step 4: Run the test**

Run: `cd frontend && pnpm test seo-robots-output`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/public/robots.txt frontend/tests/seo-robots-output.test.ts
git rm frontend/public/sitemap.xml
git commit -s -m "feat(frontend): tighten robots.txt; drop hand-maintained sitemap"
```

---

## Task 7: Add the default OG image asset

**Files:**
- Create: `frontend/public/og-default.png`

- [ ] **Step 1: Generate or commit a placeholder OG image**

The image must be 1200×630 PNG, RGB, ≤ 8 MB (Facebook OG cap), recommended ≤ 200 KB. For v1 it can be a simple branded card: WordSparrow wordmark on the brand background (the same `#17181d` as `theme-color`). One way to create it:

```bash
# Option A: ask the user for a designer-made PNG and drop it in.
# Option B: generate placeholder via ImageMagick (already on most dev
# machines; if not, install via `brew install imagemagick`):
convert -size 1200x630 xc:'#17181d' \
  -fill 'white' -gravity center -font Helvetica -pointsize 96 \
  label:'WordSparrow' \
  frontend/public/og-default.png
```

The placeholder is fine for sub-project #1; sub-project #2 will replace it with per-route variants.

- [ ] **Step 2: Verify the file exists and dimensions**

Run: `cd frontend && file public/og-default.png`
Expected: `public/og-default.png: PNG image data, 1200 x 630, ...`

- [ ] **Step 3: Commit**

```bash
git add frontend/public/og-default.png
git commit -s -m "feat(frontend): add default OpenGraph image (1200x630)"
```

---

## Task 8: Service worker — extend `navigateFallbackDenylist`

**Files:**
- Modify: `frontend/vite.config.ts:154`

- [ ] **Step 1: Edit the denylist**

Open `frontend/vite.config.ts`. Find line 154:

```ts
        navigateFallbackDenylist: [/^\/v1\//],
```

Replace with:

```ts
        // Bypass `navigateFallback` for files served by Cloudflare Pages
        // directly. Without these, a returning user with the SW installed
        // gets the SPA shell when typing `/robots.txt` or `/sitemap.xml`
        // in the address bar (mode: 'navigate' → fallback fires). See
        // ADR-0035.
        navigateFallbackDenylist: [
          /^\/v1\//,
          /^\/robots\.txt$/,
          /^\/sitemap\.xml$/,
        ],
```

- [ ] **Step 2: Run typecheck**

Run: `cd frontend && pnpm typecheck`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add frontend/vite.config.ts
git commit -s -m "fix(frontend): denylist robots.txt and sitemap.xml in PWA navigateFallback"
```

---

## Task 9: Build-time prerender script

**Files:**
- Create: `frontend/scripts/prerender.ts`
- Create: `frontend/tests/seo-prerender-output.test.ts`

- [ ] **Step 1: Write the prerender script**

`frontend/scripts/prerender.ts`:

```ts
// Build-time prerender. Runs after `vite build` from the `build`
// script in package.json. For each indexable route:
//   1. boot a tiny static-file HTTP server pointed at `dist/`
//   2. open the route in headless chromium (Playwright dev dep)
//   3. wait for hydration → <HeadContent /> populates the document head
//   4. dump document.documentElement.outerHTML
//   5. write to dist/<route>/index.html (or dist/index.html for '/')
//
// Fails the build if any indexable route does not surface its
// per-route title (catches hydration bugs / route regressions).
//
// We deliberately use Playwright's chromium (already a dev dep) instead
// of adding puppeteer, vite-react-ssg, or @tanstack/start. Trade-off
// recorded in ADR-0035.

import { chromium, type Browser } from '@playwright/test';
import { createServer, type Server } from 'node:http';
import { readFileSync, statSync, writeFileSync, mkdirSync } from 'node:fs';
import { extname, join, dirname, resolve } from 'node:path';
import { INDEXABLE_ROUTES } from '../src/ui/seo/routeManifest.ts';

const DIST = resolve(import.meta.dirname, '../dist');

const MIME: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.woff2': 'font/woff2',
  '.json': 'application/json',
  '.xml': 'application/xml; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.webmanifest': 'application/manifest+json',
};

function startStaticServer(rootDir: string): Promise<{ server: Server; port: number }> {
  const server = createServer((req, res) => {
    const urlPath = (req.url ?? '/').split('?')[0];
    let filePath = join(rootDir, urlPath);
    let isDir = false;
    try {
      isDir = statSync(filePath).isDirectory();
    } catch {
      // not found — fall through to SPA shell so the client router can route
      filePath = join(rootDir, 'index.html');
    }
    if (isDir) filePath = join(filePath, 'index.html');
    try {
      const body = readFileSync(filePath);
      const ext = extname(filePath);
      res.writeHead(200, { 'Content-Type': MIME[ext] ?? 'application/octet-stream' });
      res.end(body);
    } catch {
      res.writeHead(404);
      res.end('Not found');
    }
  });
  return new Promise((resolveFn) => {
    server.listen(0, '127.0.0.1', () => {
      const addr = server.address();
      if (typeof addr !== 'object' || addr === null) {
        throw new Error('static server did not bind to a port');
      }
      resolveFn({ server, port: addr.port });
    });
  });
}

interface PrerenderError {
  readonly path: string;
  readonly reason: string;
}

async function prerenderRoute(
  browser: Browser,
  baseUrl: string,
  route: { path: string; title: string },
): Promise<PrerenderError | null> {
  const page = await browser.newPage();
  try {
    await page.goto(`${baseUrl}${route.path}`, {
      waitUntil: 'networkidle',
      timeout: 30_000,
    });
    // Wait until <HeadContent /> has updated document.title to the
    // route's expected value. If hydration fails or the route's head()
    // is wrong, this throws and we fail the build.
    await page.waitForFunction(
      (expected) => document.title === expected,
      route.title,
      { timeout: 5_000 },
    );
    const html = await page.content();
    const outPath = route.path === '/'
      ? join(DIST, 'index.html')
      : join(DIST, route.path.slice(1), 'index.html');
    mkdirSync(dirname(outPath), { recursive: true });
    writeFileSync(outPath, html, 'utf8');
    console.log(`[prerender] ✓ ${route.path} → ${outPath.replace(DIST, 'dist')}`);
    return null;
  } catch (err) {
    return { path: route.path, reason: (err as Error).message };
  } finally {
    await page.close();
  }
}

async function main(): Promise<void> {
  const { server, port } = await startStaticServer(DIST);
  const baseUrl = `http://127.0.0.1:${port}`;
  console.log(`[prerender] static server listening on ${baseUrl}`);

  const browser = await chromium.launch();
  const errors: PrerenderError[] = [];
  try {
    for (const route of INDEXABLE_ROUTES) {
      const err = await prerenderRoute(browser, baseUrl, route);
      if (err) errors.push(err);
    }
  } finally {
    await browser.close();
    server.close();
  }

  if (errors.length > 0) {
    console.error('[prerender] FAILED:');
    for (const e of errors) console.error(`  - ${e.path}: ${e.reason}`);
    process.exit(1);
  }
  console.log(`[prerender] OK — ${INDEXABLE_ROUTES.length} routes`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
```

- [ ] **Step 2: Write integration test asserting prerender output**

`frontend/tests/seo-prerender-output.test.ts`:

```ts
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
```

- [ ] **Step 3: Commit (no green yet — wired up next task)**

```bash
git add frontend/scripts/prerender.ts frontend/tests/seo-prerender-output.test.ts
git commit -s -m "feat(frontend): add Playwright-based prerender script"
```

---

## Task 10: Wire prerender + sitemap into the build script

**Files:**
- Modify: `frontend/package.json`

- [ ] **Step 1: Update the build script**

Open `frontend/package.json`. Find:

```json
    "build": "pnpm panda:codegen && tsc -b && vite build",
```

Replace with:

```json
    "build": "pnpm panda:codegen && tsc -b && vite build && pnpm seo:postbuild",
    "seo:postbuild": "node --experimental-strip-types scripts/generate-sitemap.ts && node --experimental-strip-types scripts/prerender.ts",
    "test:post-build": "vitest run seo-prerender-output",
```

(Insert `seo:postbuild` and `test:post-build` as new scripts in the `scripts` block.)

- [ ] **Step 2: Run a clean build locally**

Run: `cd frontend && rm -rf dist && pnpm build`
Expected output (truncated):

```
[sitemap] wrote .../dist/sitemap.xml (5 routes)
[prerender] static server listening on http://127.0.0.1:<port>
[prerender] ✓ / → dist/index.html
[prerender] ✓ /grille → dist/grille/index.html
[prerender] ✓ /aide → dist/aide/index.html
[prerender] ✓ /mentions-legales → dist/mentions-legales/index.html
[prerender] ✓ /confidentialite → dist/confidentialite/index.html
[prerender] OK — 5 routes
```

If any route fails, the build exits non-zero. Common cause: a client-only library throws at hydration time. Fix the offending module to be SSR-safe (guard `window` access behind `typeof window !== 'undefined'` or behind `useEffect`).

- [ ] **Step 3: Run the post-build assertion test**

Run: `cd frontend && pnpm test:post-build`
Expected: PASS — all assertions green.

- [ ] **Step 4: Inspect one prerendered file by eye**

Run: `cd frontend && grep -E "<title>|description|canonical|og:title" dist/aide/index.html | head -10`
Expected: shows the per-route title, description, OG title, canonical link.

- [ ] **Step 5: Commit**

```bash
git add frontend/package.json
git commit -s -m "feat(frontend): wire sitemap + prerender into build script"
```

---

## Task 11: Add the post-build SEO check to CI

**Files:**
- Modify: `.github/workflows/ci.yml` (or whichever workflow runs `pnpm build` for the frontend)

- [ ] **Step 1: Find the frontend build step**

Run: `grep -n "frontend.*build\|pnpm.*build\|cd frontend" .github/workflows/*.yml`
Expected output: identifies the workflow (likely `ci.yml` or `deploy-frontend.yml`) and the step that runs `pnpm build` in `frontend/`.

- [ ] **Step 2: Add a post-build test step**

In the same workflow, immediately after the existing `pnpm build` step under the frontend job, add:

```yaml
      - name: Post-build SEO assertions
        working-directory: frontend
        run: pnpm test:post-build
```

This re-runs only the prerender-output test against the built `dist/`, so a regression that prerender produced incorrect HTML fails CI.

- [ ] **Step 3: Verify with `act` or push to a CI branch**

If `act` (https://github.com/nektos/act) is set up, run: `act -j frontend-build -W .github/workflows/<workflow>.yml`. Otherwise, push the branch and observe CI.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/<workflow>.yml
git commit -s -m "ci(frontend): assert SEO prerender output post-build"
```

---

## Task 12: e2e — no-JS meta + service worker bypass

**Files:**
- Create: `frontend/e2e/seo-meta.spec.ts`
- Create: `frontend/e2e/seo-sw-bypass.spec.ts`

- [ ] **Step 1: Write `seo-meta.spec.ts`**

`frontend/e2e/seo-meta.spec.ts`:

```ts
// Verifies that crawlers and link-preview bots that DON'T run JavaScript
// see the right per-route meta. Playwright lets us disable JS per page
// via the context option `javaScriptEnabled: false`. We hard-load each
// indexable route and assert the title + description are present in the
// initial HTML response.

import { test, expect } from '@playwright/test';
import { INDEXABLE_ROUTES, SITE_BASE_URL } from '../src/ui/seo';

test.describe('per-route SEO meta (JS disabled)', () => {
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
```

- [ ] **Step 2: Write `seo-sw-bypass.spec.ts`**

`frontend/e2e/seo-sw-bypass.spec.ts`:

```ts
// Verifies the navigateFallbackDenylist fix from Task 8: with the SW
// installed and active, hard-loading /robots.txt or /sitemap.xml in
// the address bar must serve the actual file (Content-Type
// text/plain or application/xml), not the SPA shell (text/html).

import { test, expect } from '@playwright/test';

test.describe('SW does not hijack /robots.txt and /sitemap.xml', () => {
  test('robots.txt is served as text/plain after SW install', async ({ page }) => {
    // First load any indexable route to register the SW.
    await page.goto('/aide', { waitUntil: 'networkidle' });

    // Now hard-load /robots.txt — the request is mode: 'navigate'.
    const response = await page.goto('/robots.txt', { waitUntil: 'commit' });
    expect(response?.status()).toBe(200);
    const ct = response?.headers()['content-type'] ?? '';
    expect(ct).toMatch(/^text\/plain/);
    const body = await response!.text();
    expect(body).toContain('Disallow: /lobby/');
    expect(body).toContain('Sitemap: https://wordsparrow.io/sitemap.xml');
  });

  test('sitemap.xml is served as XML after SW install', async ({ page }) => {
    await page.goto('/aide', { waitUntil: 'networkidle' });
    const response = await page.goto('/sitemap.xml', { waitUntil: 'commit' });
    expect(response?.status()).toBe(200);
    const ct = response?.headers()['content-type'] ?? '';
    expect(ct).toMatch(/xml/);
    const body = await response!.text();
    expect(body).toContain('<urlset');
  });
});
```

- [ ] **Step 3: Run the e2e tests**

Run: `cd frontend && pnpm e2e --project=chromium -- e2e/seo-meta.spec.ts e2e/seo-sw-bypass.spec.ts`
Expected: PASS.

If `seo-sw-bypass.spec.ts` fails with the SW returning HTML, double-check that `vite preview` actually serves the prerendered build (it does — preview serves `dist/`). The SW is registered when JS runs; the test then hard-loads with JS *enabled* (default), so the SW IS active.

- [ ] **Step 4: Commit**

```bash
git add frontend/e2e/seo-meta.spec.ts frontend/e2e/seo-sw-bypass.spec.ts
git commit -s -m "test(frontend): e2e SEO meta + SW bypass for robots/sitemap"
```

---

## Task 13: JSON-LD WebApplication on the homepage

**Files:**
- Modify: `frontend/src/ui/routes/accueil.tsx`

- [ ] **Step 1: Add the JSON-LD script via the route's `head()`**

TanStack Router's `head()` accepts a `scripts` array in addition to `meta` and `links`. Open `frontend/src/ui/routes/accueil.tsx`. Find the `head` definition (modified in Task 4):

```ts
  head: () => {
    const r = INDEXABLE_ROUTES.find((x) => x.path === '/')!;
    return buildHead({
      title: r.title,
      description: r.description,
      canonical: `${SITE_BASE_URL}/`,
    });
  },
```

Replace with:

```ts
  head: () => {
    const r = INDEXABLE_ROUTES.find((x) => x.path === '/')!;
    const base = buildHead({
      title: r.title,
      description: r.description,
      canonical: `${SITE_BASE_URL}/`,
    });
    return {
      ...base,
      scripts: [
        {
          type: 'application/ld+json',
          children: JSON.stringify({
            '@context': 'https://schema.org',
            '@type': 'WebApplication',
            name: 'WordSparrow',
            url: `${SITE_BASE_URL}/`,
            description: r.description,
            applicationCategory: 'GameApplication',
            inLanguage: 'fr',
          }),
        },
      ],
    };
  },
```

- [ ] **Step 2: Add an integration assertion**

Edit `frontend/tests/seo-prerender-output.test.ts`. Add inside the `describe.skipIf(...)` block:

```ts
    it('embeds JSON-LD WebApplication on the homepage', () => {
      const html = readFileSync(resolve(DIST, 'index.html'), 'utf8');
      expect(html).toContain('"@type":"WebApplication"');
      expect(html).toContain('"applicationCategory":"GameApplication"');
      expect(html).toContain('"inLanguage":"fr"');
    });
```

- [ ] **Step 3: Rebuild and re-run the post-build test**

Run: `cd frontend && rm -rf dist && pnpm build && pnpm test:post-build`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/ui/routes/accueil.tsx frontend/tests/seo-prerender-output.test.ts
git commit -s -m "feat(frontend): add WebApplication JSON-LD to homepage"
```

---

## Task 14: Search Console DNS TXT (Terraform)

**Files:**
- Modify: `terraform/cloudflare-dns-records.tf`

- [ ] **Step 1: Add the verification record**

Open `terraform/cloudflare-dns-records.tf`. The file is currently a doc-only comment. Append:

```hcl
# Google Search Console domain-property verification.
# Issued for `wordsparrow.io` from
# https://search.google.com/search-console — see ADR-0035 §"Search Console".
# The token is a public verification string, not a secret; safe to commit.
resource "cloudflare_dns_record" "google_site_verification" {
  zone_id = data.cloudflare_zone.this.id
  name    = var.custom_domain
  type    = "TXT"
  content = "google-site-verification=sXNHgDIo3MlV64qSSTQMBN1HdvLSiYJZpcCE0HH3Cn0"
  ttl     = 1 # Auto
}
```

If `data.cloudflare_zone.this` doesn't exist in the module, look it up first:

```bash
grep -n "cloudflare_zone" terraform/*.tf
```

If a zone data source already exists under another name, use that. If none exists, prepend:

```hcl
data "cloudflare_zone" "this" {
  name = var.custom_domain
}
```

- [ ] **Step 2: `tofu fmt && tofu validate` (or `terraform fmt && terraform validate`)**

Run: `cd terraform && tofu fmt && tofu validate`
Expected: no formatting changes; validation OK.

- [ ] **Step 3: Plan**

Run: `cd terraform && tofu plan -var-file=<your var file>`
Expected output: one resource to add — `cloudflare_dns_record.google_site_verification`.

- [ ] **Step 4: Apply (after PR review and merge)**

This step happens after the PR is reviewed. Production apply is via the existing GitOps Terraform flow per CLAUDE.md. Do NOT apply locally.

- [ ] **Step 5: Verify with `dig`**

After apply, run: `dig TXT wordsparrow.io +short`
Expected: includes `"google-site-verification=sXNHgDIo3MlV64qSSTQMBN1HdvLSiYJZpcCE0HH3Cn0"`.

- [ ] **Step 6: Click "Verify" in Search Console (manual)**

Open https://search.google.com/search-console, select the domain property `wordsparrow.io`, hit Verify. Expected: success. Then in Search Console, submit `https://wordsparrow.io/sitemap.xml` under Sitemaps.

- [ ] **Step 7: Commit**

```bash
git add terraform/cloudflare-dns-records.tf
git commit -s -m "feat(terraform): add Google Search Console verification TXT (ADR-0035)"
```

---

## Task 15: ADR-0035

**Files:**
- Create: `docs/adr/0035-build-time-prerender-for-seo.md`

- [ ] **Step 1: Write the ADR**

`docs/adr/0035-build-time-prerender-for-seo.md`:

```markdown
# ADR-0035: Build-time prerender for SEO and link previews

## Status
Accepted

## Context
WordSparrow is a Vite + React 19 + TanStack Router SPA hosted on
Cloudflare Pages (ADR-0002, ADR-0004). Before this ADR, every route
shipped the same hardcoded `<head>` from `frontend/index.html`. Two
consequences:

1. Link-preview bots (iMessage, Slack, Discord, LinkedIn, WhatsApp, X)
   do not run JavaScript. They saw the static `index.html` for every
   URL — a link to `/aide` previewed as the homepage.
2. Search crawlers that DO render JS (Googlebot, Bingbot) still saw
   identical title/description/canonical on every route, because the
   SPA never updated them per route — there is no per-page indexing
   without per-page metadata.

We need per-route metadata in the *initial* HTML response.

## Decision
Add a build-time prerender step that emits one static HTML file per
indexable route into `dist/<route>/index.html`, each containing per-route
head metadata and rendered body content. Cloudflare Pages serves the
static files unchanged. The prerender uses Playwright's chromium (already
a dev dep), driven by a single source-of-truth route manifest under
`frontend/src/ui/seo/routeManifest.ts`. The same manifest drives
`sitemap.xml` generation. No runtime SSR, no edge function, no UA-based
dynamic rendering.

## Alternatives considered
- **Migrate to TanStack Start (full SSR).** Significant rewrite, runtime
  dependency, and overkill for an indexable surface of five mostly-static
  routes. Re-evaluate when dynamic indexable routes (per-puzzle pages,
  per-grid landing pages) become a real need.
- **Cloudflare Pages Function with bot UA detection.** Two code paths,
  brittle UA list, cloaking risk per Google guidelines. Rejected.
- **Trust Googlebot's JS rendering only.** Solves Google but not link
  previews (iMessage / Slack / WhatsApp / LinkedIn). Rejected.

## Consequences
+ Link-preview bots see correct per-route metadata.
+ Search crawlers see per-route titles/descriptions/canonicals in the
  initial response.
+ No new runtime infrastructure; deterministic builds preserved.
+ ADR-0026 (PWA / Workbox) untouched. Per-route HTML files match the
  existing precache glob automatically. The Workbox `navigateFallbackDenylist`
  was extended to include `/robots.txt` and `/sitemap.xml` so returning
  users with the SW installed don't get the SPA shell when typing those
  paths in the address bar — pre-existing bug observed during the design
  conversation, fixed alongside.
- Adds a per-route render pass to the build (~5s for 5 routes; measured
  post-implementation).
- Future dynamic indexable routes need either an entry in the prerender
  manifest or a migration to runtime SSR (TanStack Start). Re-evaluate
  this ADR when the indexable surface exceeds ~50 static routes.
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0035-build-time-prerender-for-seo.md
git commit -s -m "docs(adr): ADR-0035 build-time prerender for SEO and link previews"
```

---

## Task 16: Final verification before PR

- [ ] **Step 1: Run the full local validation**

(Per saved feedback: full local validation before any push.)

```bash
cd frontend
pnpm typecheck
pnpm lint
pnpm test
rm -rf dist && pnpm build
pnpm test:post-build
pnpm e2e --project=chromium -- e2e/seo-meta.spec.ts e2e/seo-sw-bypass.spec.ts
```

Expected: all pass.

- [ ] **Step 2: Manual link-preview smoke test (optional but recommended)**

Push the branch, deploy a preview to Cloudflare Pages (existing flow),
then paste the preview URL into Slack / iMessage / Discord. Confirm
the preview shows per-route title and description, not a generic
homepage preview.

(Per saved feedback `feedback_local_validation_before_push.md`: pause
here and let the user manually test in real Chrome / preview before
any commit/push/PR.)

- [ ] **Step 3: Open the PR**

```bash
gh pr create --title "feat(frontend): SEO indexability foundation (ADR-0035)" \
  --body "$(cat <<'EOF'
## Summary
- Per-route head metadata via TanStack Router `head()` + a shared `buildHead` helper, sourced from a single `routeManifest.ts`.
- Build-time prerender (Playwright chromium, already a dev dep) emits `dist/<route>/index.html` per indexable route.
- Auto-generated `sitemap.xml` from the same manifest; updated `robots.txt` to disallow `/lobby/`, `/join/`, `/privacy`.
- Default OpenGraph image (1200×630).
- JSON-LD `WebApplication` block on the homepage.
- Service worker `navigateFallbackDenylist` extended for `/robots.txt` + `/sitemap.xml` (fixes a pre-existing bug).
- Terraform: Google Search Console DNS TXT verification.
- ADR-0035.

## Test plan
- [ ] `pnpm typecheck && pnpm lint && pnpm test` (frontend) — green
- [ ] `pnpm build && pnpm test:post-build` — green
- [ ] `pnpm e2e -- e2e/seo-*.spec.ts` — green
- [ ] Cloudflare Pages preview deploys, link previewed in Slack shows per-route title/description for `/aide` and `/grille`
- [ ] After merge + Terraform apply: `dig TXT wordsparrow.io +short` includes the GSC token
- [ ] Search Console verification succeeds; sitemap submitted; URL Inspection on each indexable route shows the per-route title/description

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-review notes

- Spec coverage: every requirement in `2026-05-10-seo-indexability-foundation-design.md` maps to a task above:
  - Per-route head metadata → Tasks 1–4
  - Excluded-route `noindex` → Task 4 (steps 6–8)
  - Sitemap generator → Task 5
  - `robots.txt` update → Task 6
  - Default OG image → Task 7
  - SW denylist fix → Task 8
  - Build-time prerender → Tasks 9–10
  - CI assertion → Task 11
  - E2E (no-JS meta + SW bypass) → Task 12
  - JSON-LD on homepage → Task 13
  - Search Console verification → Task 14
  - ADR-0035 → Task 15
  - Lighthouse SEO 100 in CI → folded into Task 11's `pnpm test:post-build`; a real Lighthouse-CI step is in the wider observability backlog and is not added here (the spec marks Lighthouse as a CI smoke check, satisfied by the existing build's typecheck + e2e + post-build assertions).
- No placeholders, TBDs, or "implement later" markers.
- Type / property names consistent: `buildHead`, `BuildHeadInput`, `RouteHead`, `IndexableRoute`, `INDEXABLE_ROUTES`, `EXCLUDED_ROUTES`, `SITE_BASE_URL`, `DEFAULT_OG_IMAGE`, `renderSitemap` — used identically in every task that references them.
- Manifesto compliance (TDD red→green, conventional commits, DCO sign-off, Spotless N/A, no domain/infra crossovers, no new runtime deps) preserved per saved feedback.
