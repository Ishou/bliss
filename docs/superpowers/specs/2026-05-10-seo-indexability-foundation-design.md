# SEO Indexability Foundation — Design

**Date:** 2026-05-10
**Status:** Draft (awaiting user review)
**Sub-project:** #1 of a larger SEO programme (see "Decomposition" below)
**Related ADRs:** ADR-0002 (frontend stack), ADR-0026 (PWA offline cache), ADR-0033 (frontend OTel) — extended, not superseded. New ADR-0035 introduced by this work.

## Context

WordSparrow (`wordsparrow.io`) is a Vite + React 19 + TanStack Router SPA hosted on Cloudflare Pages. Today every route ships the same hardcoded `<head>` from `frontend/index.html`. Two consequences:

1. **Link-preview bots (iMessage, Slack, Discord, LinkedIn, WhatsApp, X) do not run JavaScript.** They see the static `index.html` for *every* URL. A link to `/aide` previews as the homepage. This is a visible pre-launch hygiene failure.
2. **Search crawlers that do render JavaScript (Googlebot, Bingbot)** still see identical `<title>` / `<description>` / `canonical` on every route, because the SPA never updates them. Per-page indexing is meaningless without per-page metadata.

The goal in this sub-project is to fix both at the lowest reasonable infra cost, and to ship the measurement plumbing (Search Console + auto-generated sitemap) so we can verify it worked and use it as the baseline for later sub-projects.

## Decomposition (full SEO programme)

This document covers sub-project **#1** only. The full decomposition, agreed during brainstorming:

1. **Indexability foundation** — this document.
2. Social/sharing polish — per-route OG images.
3. Content & target queries — landing/help copy targeting "mots fléchés" query cluster.
4. Structured data — JSON-LD `WebApplication`, `Game`, `BreadcrumbList`, `FAQPage`.
5. Measurement & ops — auto-sitemap freshness, monitoring queries (minimal slice included in #1).
6. Performance / Core Web Vitals — audit and fixes.

Each sub-project gets its own design + plan + implementation cycle.

## Goals

- Every indexable route serves the right per-route `<title>`, `<description>`, `canonical`, and OG/Twitter metadata in the *initial* HTML response.
- Excluded routes (`/lobby/*`, `/join/*`, `/privacy`) carry `noindex` and are `Disallow`ed in `robots.txt`.
- `sitemap.xml` is generated at build from a single route manifest and cannot drift from reality.
- Google Search Console is verified via Terraform-managed DNS, sitemap submitted, baseline measurements captured.
- The PWA service worker continues to work; `/robots.txt` and `/sitemap.xml` no longer get hijacked by `navigateFallback` for returning users.

## Non-goals

- Per-route OG images (sub-project #2).
- Deeper structured data beyond a single homepage `WebApplication` block (sub-project #4).
- Content/keyword copywriting (sub-project #3).
- Core Web Vitals audit (sub-project #6).
- English-language SEO. WordSparrow is French-only; the existing `/privacy` route is vestigial English copy and will be `noindex`-ed.

## Approach

Three layers, no new runtime infrastructure.

### Layer A — Per-route head metadata

Each indexable route declares its head data via TanStack Router's `head()` API in the route file itself. The `head()` function returns a typed object with `title`, `description`, `canonical`. A shared helper (`frontend/src/ui/seo/buildHead.ts`) derives the OG / Twitter / robots tags from that base object so every indexable route emits a consistent set of tags.

**Contract:** every indexable route MUST declare `title`, `description`, `canonical`. No optional fields. Build fails if a route in the indexable manifest is missing any of them.

### Layer B — Build-time prerender

A new step in the Vite build pipeline takes the indexable route manifest and emits one static `index.html` per route, containing the per-route `<head>` *and* server-rendered body content. Output:

```
dist/index.html              ← /
dist/grille/index.html       ← /grille
dist/aide/index.html         ← /aide
dist/mentions-legales/index.html
dist/confidentialite/index.html
```

Tool choice (e.g. `vite-react-ssg`, a custom Puppeteer-based script, or another) is deferred to the implementation plan. The design specifies the contract — *what* must be emitted — not the *how*. Whichever tool we pick must:

- Produce byte-stable output across two consecutive runs (deterministic builds, manifesto §"Developer Experience").
- Fail the build if any indexable route fails to prerender.
- Not introduce a runtime dependency (no SSR server, no edge function).

**Body content expectations.** For mostly-static routes (`/`, `/aide`, `/mentions-legales`, `/confidentialite`), the prerendered body contains the route's full rendered output. For `/grille`, the prerendered body contains the route shell (header, layout, copy) but not a specific puzzle — the puzzle is fetched at runtime per existing behavior. Static body content is a bonus for SEO; the load-bearing requirement is per-route head metadata.

### Layer C — Delivery

Cloudflare Pages serves the static files exactly as today. No new runtime, no edge function. The PWA service worker precaches each per-route HTML automatically via the existing Workbox `globPatterns: ['**/*.{js,css,html,svg,png,woff2,webmanifest}']` rule.

## Indexable surface

| Route | Indexed? | Rationale |
|---|---|---|
| `/` | Yes | Landing page. |
| `/grille` | Yes | Primary play surface. Daily grid. |
| `/aide` | Yes | Help / how-to-play. Targets long-tail "comment jouer mots fléchés" queries. |
| `/mentions-legales` | Yes | Required for FR commercial sites. Low priority but safe to index. |
| `/confidentialite` | Yes | Privacy policy (FR). |
| `/privacy` | **No** (`noindex,follow` + Disallow) | Vestigial EN copy. FR-only target audience per ADR-0005. |
| `/lobby/$lobbyId` | **No** | Ephemeral, dynamic, no SEO value, contains lobby codes per ADR-0027. |
| `/join/$code` | **No** | Same. |

Future indexable routes (TBD) will be added by extending the route manifest and declaring `head()` on the route file.

## Per-route head metadata (initial values)

Copy will iterate. These are the v1 anchors.

| Route | `<title>` | `description` (≤ 160 chars, FR) | `canonical` |
|---|---|---|---|
| `/` | `WordSparrow — mots fléchés français en ligne` | `Jouez aux mots fléchés en français, en solo ou en multijoueur. Gratuit, sans inscription.` | `https://wordsparrow.io/` |
| `/grille` | `Grille du jour — WordSparrow` | `Résolvez la grille de mots fléchés du jour, en français.` | `https://wordsparrow.io/grille` |
| `/aide` | `Aide — WordSparrow` | `Comment jouer aux mots fléchés sur WordSparrow : règles, astuces, raccourcis.` | `https://wordsparrow.io/aide` |
| `/mentions-legales` | `Mentions légales — WordSparrow` | `Mentions légales et informations éditoriales de WordSparrow.` | `https://wordsparrow.io/mentions-legales` |
| `/confidentialite` | `Confidentialité — WordSparrow` | `Politique de confidentialité de WordSparrow.` | `https://wordsparrow.io/confidentialite` |

### Derived OG / Twitter (every indexable route)

- `og:title` = `<title>`
- `og:description` = `description`
- `og:url` = `canonical`
- `og:image` = `https://wordsparrow.io/og-default.png` — single shared 1200×630 PNG, committed to `frontend/public/og-default.png`. Per-route variants deferred to sub-project #2.
- `og:type=website`, `og:site_name=WordSparrow`, `og:locale=fr_FR`
- `twitter:card=summary_large_image`, `twitter:title`, `twitter:description`, `twitter:image` (mirror of the OG values)

### Excluded-route meta

`/privacy`, `/lobby/$lobbyId`, `/join/$code` MUST emit `<meta name="robots" content="noindex,follow">`. Three delivery paths depending on how the route is served:

- `/privacy` is *not* prerendered (excluded from the manifest). When a crawler hard-loads it, the SPA shell (`dist/index.html`) is served by `navigateFallback`. The route's `head()` injects the `noindex` meta on hydration. Because crawlers that respect `noindex` either render JS (Googlebot) or honor the `Disallow` from `robots.txt`, this combination is sufficient.
- `/lobby/$lobbyId` and `/join/$code` are dynamic and SPA-only by design. Same mechanism: `head()` on the route injects `noindex` on hydration; `Disallow` in `robots.txt` is the primary defense.
- The `Disallow` lines in `robots.txt` are the load-bearing protection. The `noindex` meta is belt-and-suspenders.

### JSON-LD on `/` only

Single inline `<script type="application/ld+json">` with a `WebApplication` block:

```json
{
  "@context": "https://schema.org",
  "@type": "WebApplication",
  "name": "WordSparrow",
  "url": "https://wordsparrow.io/",
  "description": "Mots fléchés français, solo et multijoueur.",
  "applicationCategory": "GameApplication",
  "inLanguage": "fr"
}
```

Deeper structured data (`FAQPage` for `/aide`, `BreadcrumbList`, `Game`) deferred to sub-project #4.

## `sitemap.xml`

Generated at build from the indexable route manifest. Replaces `frontend/public/sitemap.xml` (deleted from `public/`, emitted to `dist/`).

- One `<url>` per indexable route.
- `<loc>` = absolute URL (`https://wordsparrow.io${route}`).
- `<lastmod>` = ISO date of the most recent git commit touching the route's source file (route component, head data, or co-located content). Derived at build time via `git log -1 --format=%cI -- <path>`. Falls back to build date if git is unavailable (e.g., shallow clone in CI without history — in that case the build also warns).
- `<changefreq>` and `<priority>` omitted. Google ignores them.

Excluded routes are absent from the sitemap.

## `robots.txt`

Replaces `frontend/public/robots.txt`:

```
User-agent: *
Allow: /
Disallow: /lobby/
Disallow: /join/
Disallow: /privacy

Sitemap: https://wordsparrow.io/sitemap.xml
```

Generated at build (or kept static — this is small enough either way; design recommends static-but-test-asserted content for clarity).

## Service worker interaction

**Already-correct (no config change needed):**
- Per-route prerendered HTML files match the existing `globPatterns: ['**/*.{js,css,html,svg,png,woff2,webmanifest}']` rule and are precached automatically.
- `navigateFallback: '/index.html'` (`vite.config.ts:153`) keeps firing only for routes *not* in precache. After prerender, `/aide` is in precache → SW serves prerendered `/aide`. `/lobby/$lobbyId` is not → falls back to `/index.html` and the SPA boots. Correct in both cases.

**Fix required (also fixes the bug observed 2026-05-10):**
- Extend `navigateFallbackDenylist` from `[/^\/v1\//]` to `[/^\/v1\//, /^\/robots\.txt$/, /^\/sitemap\.xml$/]`. Without this, returning users typing `/robots.txt` in the address bar get the SPA shell because the request is `mode: 'navigate'`.

## Search Console & measurement

### Verification

Add a `TXT` record to `wordsparrow.io` via `terraform/cloudflare-dns-records.tf`:

- Name: `@`
- Value: `google-site-verification=sXNHgDIo3MlV64qSSTQMBN1HdvLSiYJZpcCE0HH3Cn0`
- TTL: default

Apply via the existing Terraform GitOps flow. Bing Webmaster Tools deliberately not added — out of scope, low marginal value for a French-only site.

### Submission

Once verified, submit `https://wordsparrow.io/sitemap.xml` once in the Search Console UI. One-time manual step (no Terraform resource for sitemap submission).

### What we monitor

- **Coverage report** — every indexable route shows up as "Indexed" within 14 days. If `/grille` stays "Discovered – currently not indexed", we have a content quality problem to fix in sub-project #3.
- **URL Inspection → Test Live URL** — for each indexable route, verify GSC sees the per-route `<title>`, `<description>`, and rendered body. Acceptance test for prerender working in production.
- **Performance report** — capture impressions baseline for the "mots fléchés" cluster (target queries: "mots fléchés en ligne", "mots fléchés français", "mots fléchés gratuit"). Baseline for sub-project #3.

No SigNoz/Grafana plumbing for SEO metrics — over-investment at this stage. SEO data lives in GSC.

### Success criteria for this sub-project

1. All 5 indexable routes appear as "Indexed" in GSC within 14 days of deploy.
2. URL Inspection on each indexable route shows the per-route `<title>` and `<description>` (proves prerender works end-to-end in production).
3. Sharing each indexable URL in Slack and iMessage produces a preview with that route's title and description (proves OG works for non-JS bots).
4. Lighthouse SEO score = 100 on every indexable route in CI.

## Risks

1. **Hydration mismatch.** If prerendered body differs from client first-render, React 19 throws a hydration error. Mitigation: prerender uses the same React entry point as the client, no `Date.now()` / `Math.random()` in the render path. Test gate: `renderToString(<App route="/aide">)` byte-stable across two runs.

2. **Client-only libraries breaking SSR.** `react-zoom-pan-pinch` (ADR-0016), `zag-js` machines, `mockServiceWorker`, anything touching `window` at import time. Mitigation (chosen at plan time): either guard behind dynamic `import()` inside `useEffect`, or run prerender in a JSDOM-shimmed environment.

3. **Analytics / OTel double-init.** `frontend/src/main.tsx:214` calls `tracker.trackPageView`. If that runs during prerender it could bake state into HTML or hit network. Mitigation: gate analytics + OTel init on `typeof window !== 'undefined'`.

4. **Stale lastmod / sitemap drift.** Mitigation: sitemap generation is part of `vite build`, runs in CI on every deploy, can never go out of sync with the route manifest. Test gate: build fails if a route in the manifest is missing from the sitemap output.

5. **Build time regression.** Prerendering adds a per-route render pass. With 5 routes, expected <5s added to the build. Acceptable per CLAUDE.md "CI under 5 minutes" target. Will measure post-implementation.

6. **PWA cache size.** Each prerendered HTML adds ~2-5 KB to the precache. 5 routes × 5 KB = ~25 KB. Negligible.

## Testing

TDD ordering — tests first, red → green → refactor (CLAUDE.md "Testing" rules).

| Layer | Test | Path |
|---|---|---|
| Unit | Each indexable route's `head()` returns `title`, `description`, `canonical`. Canonical equals `https://wordsparrow.io${route}`. | `frontend/tests/unit/seo/route-head.test.ts` |
| Unit (property) | Sitemap generator: given route manifest `[a, b, c]`, output is valid XML containing exactly those `<loc>` entries with correct absolute URLs. Property-tested over arbitrary subsets. | `frontend/tests/unit/seo/sitemap.test.ts` |
| Integration | After `vite build`, `dist/<route>/index.html` exists and contains the expected `<title>`, `og:title`, `og:url`, canonical link. One assertion per indexable route. | `frontend/tests/integration/seo/prerender-output.test.ts` |
| Integration | After `vite build`, `dist/sitemap.xml` is valid against the sitemap XSD and lists exactly the indexable routes. Excluded routes (`/lobby/...`, `/privacy`) absent. | `frontend/tests/integration/seo/sitemap-output.test.ts` |
| Integration | After `vite build`, `dist/robots.txt` Disallows `/lobby/`, `/join/`, `/privacy` and references the sitemap URL. | `frontend/tests/integration/seo/robots-output.test.ts` |
| E2E | Hard-load `/aide` in Playwright with JS *disabled*; assert `<title>` and `<meta name="description">` are present and correct. Proves crawlers/link-preview bots see them. | `frontend/tests/e2e/seo/no-js-meta.spec.ts` |
| E2E | Load `/robots.txt` in a browser with the existing SW installed; assert `Content-Type: text/plain` and body matches the file. Proves the denylist fix. | `frontend/tests/e2e/seo/sw-bypass.spec.ts` |
| CI smoke | Lighthouse SEO score = 100 on every indexable route. | Existing Lighthouse CI step, extended. |

The sitemap generator test is the first red test. Mocks: none — all units are pure or hit the file system, tested with real I/O per CLAUDE.md ("Mock external boundaries only").

## Manifesto compliance

| Manifesto rule | How this design complies |
|---|---|
| Domain ↔ infra separation | All work confined to the `frontend` bounded context (`frontend/src/ui/seo/*`, `frontend/scripts/*`, `frontend/vite.config.ts`). No domain code touched. |
| TDD | Tests precede implementation. Red → green → refactor. Sitemap generator is the first red test. |
| Mock external boundaries only | No mocks. All units pure or filesystem-tested with real I/O. |
| ADR for non-trivial decisions | New ADR-0035 — Build-time prerender for SEO and link previews. Captures the "static prerender, no runtime" decision and the rejected alternatives (TanStack Start SSR, Cloudflare Pages Function bot detection). Extends ADR-0002 and ADR-0026. |
| Conventional commits | `feat(frontend): per-route head + prerender (ADR-0035 §X)` and similar. Branch `feat/seo-indexability-foundation`. |
| DCO sign-off | Every commit signed off (`git commit -s`). |
| Observability via OTel agent / structured logs | Build-time only — no runtime observability concerns. Prerender script logs structured warnings on stale-lastmod fallback. |
| Privacy by design | Prerendered HTML is fully public, contains no user data. |
| Spotless / Prettier / ESLint | ESLint + Prettier already enforced for `frontend`. |
| Run local validation before push | Standard: typecheck, lint, vitest, e2e, build before any push (per saved feedback). |

## ADR-0035 (sketch)

To be authored alongside the implementation PR.

```
# ADR-0035: Build-time prerender for SEO and link previews

## Status
Proposed

## Context
WordSparrow is a Vite + React 19 + TanStack Router SPA on Cloudflare Pages.
Crawlers and link-preview bots see only the static index.html, regardless of
route. We need per-route metadata in the initial HTML response to (a) stop
shipping a homepage preview for every shared link, and (b) enable per-page
indexing.

## Decision
Add a build-time prerender step that renders each indexable route to a static
HTML file containing per-route head metadata and server-rendered body
content. Cloudflare Pages serves the static files unchanged. No runtime SSR,
no edge function, no UA-based dynamic rendering.

## Consequences
+ Link-preview bots see correct per-route metadata.
+ Search crawlers see per-route titles/descriptions in the initial response.
+ No new runtime infrastructure; deterministic builds preserved.
+ ADR-0026 (PWA / Workbox) untouched — prerendered HTML files match the
  existing precache glob automatically.
- Adds a per-route render pass to the build (~5s for 5 routes; measured
  post-implementation).
- Future dynamic indexable routes (e.g. per-puzzle pages) require either
  extending the prerender manifest or migrating to runtime SSR (TanStack
  Start). Re-evaluate when the indexable surface exceeds ~50 static routes.
```

## Open questions

None blocking. Tool choice for the prerender (Puppeteer-based vs `vite-react-ssg` vs other) is deferred to the implementation plan, since it does not change any external contract.

## Out of scope

- Per-route OG image generation (sub-project #2).
- FAQ / Breadcrumb / Game JSON-LD beyond the homepage `WebApplication` block (sub-project #4).
- Content / keyword copywriting (sub-project #3).
- Core Web Vitals / Lighthouse Performance audit (sub-project #6).
- English-language SEO.
- Bing Webmaster Tools.
