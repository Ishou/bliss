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

// Canonical example payload for `GET /v1/puzzles/daily` (and
// `GET /v1/puzzles/{puzzleId}`). Mirrors the OpenAPI example committed
// next to the spec — same fixture MSW seeds preview deploys with — so
// the prerender renders against the exact wire shape the contract
// promises. Read once at module load; reused across every page.
const PUZZLE_FIXTURE_PATH = resolve(
  import.meta.dirname,
  '../../grid/api/examples/get-puzzle-200.json',
);
const PUZZLE_FIXTURE_BODY = readFileSync(PUZZLE_FIXTURE_PATH, 'utf8');

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

async function prerenderRoute(
  context: BrowserContext,
  baseUrl: string,
  route: { path: string; title: string },
): Promise<PrerenderError | null> {
  const page = await context.newPage();
  try {
    // Routes with a data loader (`/`, `/grille`) call
    // `puzzleRepository.fetchDaily()` against the real prod API host
    // (`VITE_GRID_API_URL` = `https://api.wordsparrow.io`). Two things
    // we need from the network layer here:
    //   1. Mock `/v1/puzzles/**` with a realistic payload so the loader
    //      resolves and the *real* component renders. Otherwise the
    //      route's `errorComponent` mounts ("Une erreur est survenue")
    //      and that error markup gets baked into `dist/index.html` and
    //      `dist/grille/index.html` — bad SEO and a guaranteed
    //      hydration mismatch when the client loader succeeds for real.
    //   2. Fail every other external request fast (Matomo, OTel, any
    //      future analytics endpoint). Letting them hang holds the
    //      page open; a synthetic 503 lets the SDK no-op.
    // Local static-server requests (the bundle, sitemap, OG image,
    // etc.) pass through untouched.
    //
    // Order matters: Playwright uses LIFO dispatch — the last-registered
    // route handler has highest priority. We register the broad catch-all
    // first (lowest priority) and the puzzle stub second (highest priority),
    // so puzzle requests are fulfilled with the fixture before the
    // catch-all can return 503.
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
    await page.route('**/v1/puzzles/**', (route_) =>
      route_.fulfill({
        status: 200,
        contentType: 'application/json',
        body: PUZZLE_FIXTURE_BODY,
      }),
    );
    await page.goto(`${baseUrl}${route.path}`, {
      waitUntil: 'domcontentloaded',
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
    console.warn(`[prerender] ok ${route.path} -> ${outPath.replace(DIST, 'dist')}`);
    return null;
  } catch (err) {
    return { path: route.path, reason: (err as Error).message };
  } finally {
    await page.close();
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
  console.warn(`[prerender] OK — ${INDEXABLE_ROUTES.length} routes`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
