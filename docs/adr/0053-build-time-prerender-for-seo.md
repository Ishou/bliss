# ADR-0053: Build-time prerender for SEO and link previews

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
static files unchanged. The prerender uses Playwright's chromium
(already a dev dep), driven by a single source-of-truth route manifest
under `frontend/src/ui/seo/routeManifest.ts`. The same manifest drives
`sitemap.xml` generation. No runtime SSR, no edge function, no UA-based
dynamic rendering.

The prerender script (`frontend/scripts/prerender.ts`) opens each route
in headless chromium with `serviceWorkers: 'block'` (crawlers don't
register SWs anyway) and intercepts external network requests. Calls to
the puzzle API (`/v1/puzzles/**`) are stubbed with the OpenAPI
example payload from `grid/api/examples/get-puzzle-200.json` so the
loader-bearing routes (`/`, `/grille`) render their real component
instead of the route's `errorComponent`. All other external URLs (Matomo,
OTel ingest, Cloudflare beacons, etc.) return HTTP 503 and the page
proceeds. The prerender waits for `document.title` to match the manifest
title before dumping `document.documentElement.outerHTML` — a hydration
or routing regression therefore fails the build.

## Alternatives considered
- **Migrate to TanStack Start (full SSR).** Significant rewrite, runtime
  dependency, and overkill for an indexable surface of five mostly-static
  routes. Re-evaluate when dynamic indexable routes (per-puzzle pages,
  per-grid landing pages) become a real need.
- **Cloudflare Pages Function with bot UA detection.** Two code paths,
  brittle UA list, cloaking risk per Google guidelines. Rejected.
- **Trust Googlebot's JS rendering only.** Solves Google but not link
  previews (iMessage / Slack / WhatsApp / LinkedIn). Rejected.
- **`vite-react-ssg` or similar plugin.** Adds a dependency and a
  framework-specific entry-point dance. Playwright was already in
  devDependencies for e2e, so reusing it costs zero new packages and
  zero new runtime concepts.

## Consequences
+ Link-preview bots see correct per-route metadata (title, description,
  canonical, OG, Twitter card).
+ Search crawlers see per-route titles/descriptions/canonicals in the
  initial response. JSON-LD `WebApplication` is embedded on the homepage
  for richer SERP eligibility.
+ No new runtime infrastructure; deterministic builds preserved.
+ ADR-0026 (PWA / Workbox) untouched. Per-route HTML files match the
  existing precache glob automatically. The Workbox `navigateFallbackDenylist`
  was extended to include `/robots.txt` and `/sitemap.xml` so returning
  users with the SW installed don't get the SPA shell when typing those
  paths in the address bar — pre-existing bug observed during the design
  conversation, fixed alongside.
+ `sitemap.xml` is auto-generated at build from the same route manifest.
  It cannot drift from the routes the app actually indexes.
- Adds a per-route render pass to the build (~5–7 s for 5 routes; well
  inside the manifesto's "CI under 5 min" target).
- The prerender's puzzle-API stub means the prerendered body for `/`
  and `/grille` reflects the example payload, not whatever the live
  daily puzzle is at deploy time. Acceptable: crawlers and link-preview
  bots care about head metadata, not the specific puzzle.
- Future dynamic indexable routes need either an entry in the prerender
  manifest or a migration to runtime SSR (TanStack Start). Re-evaluate
  this ADR when the indexable surface exceeds ~50 static routes.

## Search Console

Verifying the `wordsparrow.io` domain property in Google Search Console
requires a TXT record at the apex. The record
(`google-site-verification=sXNHgDIo3MlV64qSSTQMBN1HdvLSiYJZpcCE0HH3Cn0`)
is managed by the Terraform Cloudflare DNS module
(`terraform/cloudflare-dns-records.tf`) and is gated on
`cloudflare_zone_id`/`custom_domain` being non-empty — matching the guard
pattern of the rest of the DNS-side IaC so a bootstrap apply is still a
no-op. The token is a public verification string, not a secret; safe to
commit.

Once verified, `https://wordsparrow.io/sitemap.xml` is submitted under
Sitemaps. Acceptance criterion: all five indexable routes appear as
"Indexed" in the Coverage report within 14 days of verification.
