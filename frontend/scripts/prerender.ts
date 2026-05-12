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

import { chromium, type BrowserContext } from '@playwright/test';
import { createServer, type Server } from 'node:http';
import { readFileSync, statSync, writeFileSync, mkdirSync } from 'node:fs';
import { extname, join, dirname, resolve } from 'node:path';
import { INDEXABLE_ROUTES } from '../src/ui/seo/routeManifest.ts';

const DIST = resolve(import.meta.dirname, '../dist');

// Routes whose loader fetches the daily puzzle. Their prerendered HTML
// must NOT bake the fixture-puzzle body — the real puzzle (size,
// number, difficulty, persisted progress) differs, and on F5 the user
// would see a fixture-then-real swap. We render these in two passes
// (see `prerenderRoute`): pass A loads with the fixture so head() fires
// and produces the real meta / OG / JSON-LD; pass B leaves the puzzle
// endpoint hanging so the route's pendingComponent (skeleton) renders;
// we graft pass A's <head> onto pass B's body.
const PUZZLE_LOADING_ROUTES: ReadonlySet<string> = new Set(['/', '/grille']);

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

function startStaticServer(
  rootDir: string,
  originalShell: string,
): Promise<{ server: Server; port: number }> {
  const server = createServer((req, res) => {
    const urlPath = (req.url ?? '/').split('?')[0];
    // Always serve the in-memory shell for `/` and `/index.html`. Without
    // this, once the homepage's prerender writes its post-hydration HTML
    // to `dist/index.html`, every subsequent route's SPA-shell load would
    // pick up the homepage's per-route head tags (og:image, canonical,
    // <title>, etc.) and `<HeadContent />` would inject the route's own
    // tags on top — leaving duplicate head tags in the dumped outerHTML.
    if (urlPath === '/' || urlPath === '/index.html') {
      res.writeHead(200, { 'Content-Type': MIME['.html']! });
      res.end(originalShell);
      return;
    }
    let filePath = join(rootDir, urlPath);
    let isDir = false;
    let notFound = false;
    try {
      isDir = statSync(filePath).isDirectory();
    } catch {
      notFound = true;
    }
    if (notFound) {
      // SPA fallback: serve the in-memory clean shell so the client
      // router can resolve the route from a duplicate-free starting state.
      res.writeHead(200, { 'Content-Type': MIME['.html']! });
      res.end(originalShell);
      return;
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

// Canonical example payload for `GET /v1/puzzles/daily` (and
// `GET /v1/puzzles/{puzzleId}`). Mirrors the OpenAPI example committed
// next to the spec — same fixture MSW seeds preview deploys with — so
// the loader resolves and head() can fire (head() runs after the loader
// per TanStack Router's executeHead order). For puzzle-loading routes
// we then do a second pass with the endpoint hanging so the body is the
// skeleton; we merge the first pass's <head> over the second pass's body.
const PUZZLE_FIXTURE_PATH = resolve(
  import.meta.dirname,
  '../../grid/api/examples/get-puzzle-200.json',
);
const PUZZLE_FIXTURE_BODY = readFileSync(PUZZLE_FIXTURE_PATH, 'utf8');

type PuzzleStub = 'fixture' | 'hang';

async function loadRoute(
  context: BrowserContext,
  baseUrl: string,
  route: { path: string; title: string },
  puzzleStub: PuzzleStub,
): Promise<string> {
  const page = await context.newPage();
  try {
    // Fail every external request fast (Matomo, OTel, any future
    // analytics endpoint). Letting them hang holds the page open; a
    // synthetic 503 lets the SDK no-op. Local static-server requests
    // (the bundle, sitemap, OG image, etc.) pass through untouched.
    await page.route('**/*', (route_) => {
      const url = route_.request().url();
      if (url.startsWith(baseUrl)) {
        return route_.continue();
      }
      return route_.fulfill({
        status: 503,
        contentType: 'application/json',
        body: '{"error":"prerender-no-network"}',
      });
    });
    // Order matters: Playwright uses LIFO dispatch — last-registered
    // route handler has highest priority. The puzzle stub runs before
    // the broader catch-all above.
    if (puzzleStub === 'fixture') {
      await page.route('**/v1/puzzles/**', (route_) =>
        route_.fulfill({
          status: 200,
          contentType: 'application/json',
          body: PUZZLE_FIXTURE_BODY,
        }),
      );
    } else {
      await page.route('**/v1/puzzles/**', () => {
        // Intentional no-op: leave the request hanging so the route's
        // pendingComponent (skeleton) renders.
      });
    }
    await page.goto(`${baseUrl}${route.path}`, {
      waitUntil: 'domcontentloaded',
      timeout: 30_000,
    });
    // Wait for the route to settle. Fixture pass: head() fires after
    // the loader resolves and updates <title>. Hang pass: the
    // skeleton's imperative useLayoutEffect sets the title (head() never
    // fires while the loader pends). Either way, title === expected
    // signals the page is ready to dump.
    await page.waitForFunction(
      (expected) => document.title === expected,
      route.title,
      { timeout: 5_000 },
    );
    if (puzzleStub === 'hang') {
      // Belt-and-braces: ensure the skeleton's status sentinel
      // ("Chargement…" / "Chargement de la grille…") is in the DOM
      // before dumping. pendingComponent only mounts after TanStack
      // Router's pendingMs elapses (200 ms on these routes).
      await page.waitForSelector('main [role="status"]', { timeout: 5_000 });
    }
    return await page.content();
  } finally {
    await page.close();
  }
}

// Graft the fully-loaded route's head metadata onto the skeleton body so
// the prerendered HTML keeps complete SEO + share-preview tags without
// baking the fixture grid. Two layers to preserve:
//   1. The <head> block (title, meta, link, canonical, OG/Twitter cards).
//   2. <script type="application/ld+json"> tags. React 19 hoists <title>
//      / <meta> / <link> rendered inline up to <head>, but does NOT
//      hoist inline <script>, so JSON-LD blocks stay in the body where
//      <HeadContent /> renders them (inside #root). Without this second
//      pass we silently lose Game / BreadcrumbList / Organization
//      schema on /grille and /.
function mergeHeadIntoBody(metaHtml: string, bodyHtml: string): string {
  const headMatch = /<head[^>]*>([\s\S]*?)<\/head>/.exec(metaHtml);
  if (!headMatch) {
    throw new Error('mergeHeadIntoBody: no <head> in source HTML');
  }
  let merged = bodyHtml.replace(/<head[^>]*>[\s\S]*?<\/head>/, () => headMatch[0]);
  const ldJsonRe = /<script\s+type="application\/ld\+json"[^>]*>[\s\S]*?<\/script>/g;
  const ldJsonScripts = metaHtml.match(ldJsonRe);
  if (ldJsonScripts && ldJsonScripts.length > 0) {
    // Inject just inside #root so the scripts are part of the
    // hydratable React tree (matches where HeadContent emitted them in
    // pass A). React replaces them on hydration with the same content,
    // so no mismatch warning fires.
    merged = merged.replace(
      /<div id="root">/,
      () => `<div id="root">${ldJsonScripts.join('')}`,
    );
  }
  return merged;
}

async function prerenderRoute(
  context: BrowserContext,
  baseUrl: string,
  route: { path: string; title: string },
): Promise<PrerenderError | null> {
  try {
    const fullHtml = await loadRoute(context, baseUrl, route, 'fixture');
    let html = fullHtml;
    if (PUZZLE_LOADING_ROUTES.has(route.path)) {
      // Second pass: render the skeleton (hang the puzzle endpoint),
      // then graft the first pass's <head> onto it. This avoids baking
      // the fixture grid into the prerendered HTML — on F5 the user
      // would otherwise see a fixture-then-real grid swap — while
      // keeping the head metadata that crawlers / share previews need.
      const skeletonHtml = await loadRoute(context, baseUrl, route, 'hang');
      html = mergeHeadIntoBody(fullHtml, skeletonHtml);
    }
    const outPath = route.path === '/'
      ? join(DIST, 'index.html')
      : join(DIST, route.path.slice(1), 'index.html');
    mkdirSync(dirname(outPath), { recursive: true });
    writeFileSync(outPath, html, 'utf8');
    console.warn(`[prerender] ok ${route.path} -> ${outPath.replace(DIST, 'dist')}`);
    return null;
  } catch (err) {
    return { path: route.path, reason: (err as Error).message };
  }
}

async function main(): Promise<void> {
  // Pin the original Vite-built shell in memory before any prerender pass
  // writes to dist/. Every route's prerender starts from this shell — no
  // previously-written page's per-route head tags leak into the next
  // route's SPA-shell load. After all routes prerender, the homepage's
  // HTML overwrites `dist/index.html` last (so direct hits on `/` see
  // the homepage's per-route tags as expected).
  //
  // The shell itself only carries non-route-specific head bits (charset,
  // viewport, theme-color, favicons, manifest). `<HeadContent />` appends
  // new <title>, <meta>, and <link> elements rather than replacing
  // existing ones, so the shell must NOT ship per-route defaults
  // (<title>, og:*, twitter:*, description, canonical) — they'd
  // duplicate in every dumped outerHTML.
  const originalShell = readFileSync(join(DIST, 'index.html'), 'utf8');
  const { server, port } = await startStaticServer(DIST, originalShell);
  const baseUrl = `http://127.0.0.1:${port}`;
  console.warn(`[prerender] static server listening on ${baseUrl}`);

  const browser = await chromium.launch();
  // Block service-worker registration. The prod build registers
  // workbox via vite-plugin-pwa, and the SW intercepts every fetch
  // (including the route loader's API call). With it active, even a
  // synthesised network failure stays trapped inside the SW, the
  // loader never rejects, and the route stays on its pendingComponent
  // forever — head() never fires. Blocking SWs at the context level
  // keeps the runtime page identical to the bundle a search-engine
  // crawler sees on a cold visit (no SW yet).
  const context = await browser.newContext({ serviceWorkers: 'block' });
  // Mark the onboarding tour as already-seen before any page script
  // runs. Without this, `useSoloTour` reads an empty localStorage,
  // auto-opens the welcome step, and the Portal-rendered backdrop /
  // spotlight / positioner / content get baked into
  // `dist/grille/index.html` as `data-state="open"` markup. Real
  // visitors then load that static HTML on a hard refresh, see the
  // open tour for a frame, and find it frozen — Portal-rendered DOM
  // sits outside the route's hydratable subtree, so React on the
  // client never adopts those nodes and no event handlers attach.
  // Key + value mirror `infrastructure/session/localStorageTour.ts`
  // (`TOUR_SEEN_KEY` / encodes truthy as the literal string `'true'`).
  await context.addInitScript(() => {
    try {
      localStorage.setItem('wordsparrow.tour.seen', 'true');
    } catch {
      // Sandboxed contexts (none in CI) — fall through.
    }
  });
  const errors: PrerenderError[] = [];
  try {
    for (const route of INDEXABLE_ROUTES) {
      const err = await prerenderRoute(context, baseUrl, route);
      if (err) errors.push(err);
    }
  } finally {
    await context.close();
    await browser.close();
    server.close();
  }

  if (errors.length > 0) {
    console.error('[prerender] FAILED:');
    for (const e of errors) console.error(`  - ${e.path}: ${e.reason}`);
    process.exit(1);
  }
  // SPA fallback shell. Cloudflare Pages's `public/_redirects` rule
  // rewrites every path without a matching prerendered file (e.g.
  // `/lobby/<id>`, `/join/<code>`) to this document. We write it
  // AFTER the prerender pass on purpose — `originalShell` is the
  // Vite-built shell captured before any route prerendered into
  // `dist/index.html`, so it carries no per-route head tags.
  //
  // Three previous attempts looped or downloaded; this is the fourth:
  //   1. `/200.html` — CF strips `.html` → `/200` → no file →
  //      re-rewrite → loop (ERR_TOO_MANY_REDIRECTS).
  //   2. `/_spa-shell/` (directory) — CF strips trailing `/` →
  //      `/_spa-shell` → no file at that exact path → loop.
  //   3. `/_spa-shell` (extension-less) — no loop, but CF served it
  //      with `application/octet-stream` (no extension to MIME-sniff
  //      from), `_headers` Content-Type override was ignored, the
  //      browser downloaded the file instead of rendering it.
  //   4. `/_spa-shell.htm` — CF's "Pretty URLs" canonicalization
  //      targets `.html` specifically (and `/index.html`); `.htm` is
  //      an alternate HTML extension recognized by CF's MIME table
  //      as `text/html` but NOT subject to the strip rule. The URL
  //      stays as `/_spa-shell.htm`, the file resolves directly, the
  //      response carries `text/html`, the browser renders.
  writeFileSync(join(DIST, '_spa-shell.htm'), originalShell, 'utf8');
  console.warn('[prerender] wrote SPA fallback shell at dist/_spa-shell.htm');
  console.warn(`[prerender] OK — ${INDEXABLE_ROUTES.length} routes`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
