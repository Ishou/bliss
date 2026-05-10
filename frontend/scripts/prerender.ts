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
  context: BrowserContext,
  baseUrl: string,
  route: { path: string; title: string },
): Promise<PrerenderError | null> {
  const page = await context.newPage();
  try {
    // Block every outbound request that doesn't target the local
    // static server. Routes with a data loader (`/`, `/grille`) call
    // `puzzleRepository.fetchDaily()` against the real prod API host;
    // letting that network call run pins the route on the
    // pendingComponent indefinitely (or breaks the build behind a
    // strict egress firewall). Forcing the loader to fail fast lets
    // the route's `errorComponent` mount, which is what surfaces the
    // route's `head()` (title / canonical / description) into the DOM
    // — exactly what the prerender wants to capture.
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
  const { server, port } = await startStaticServer(DIST);
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
