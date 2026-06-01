import { defineConfig, type Plugin } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { FontaineTransform } from 'fontaine';
import { VitePWA } from 'vite-plugin-pwa';

// Vite + React 19 config for the Bliss frontend bounded context.
// See ADR-0002 for the stack rationale.
//
// Two FOUT-mitigation pieces are wired here (originally introduced by
// `fix/frontend-font-flicker`; three-family roster per ADR-0043 §3):
//
//   1. `FontaineTransform` rewrites every `@font-face` rule in
//      `src/ui/styles/fonts.css` to add a sibling face named
//      `"<Family> Variable fallback"` whose `size-adjust`,
//      `ascent-override`, `descent-override`, and `line-gap-override`
//      make the system fallback occupy the same pixels as the web
//      font. Currently emits faces for Fraunces Variable (heading)
//      and Outfit Variable (body); the plugin auto-detects families
//      from the CSS, so adding/removing a family in `fonts.css` is
//      all the wiring needed.
//
//   2. `preloadLatinBodyFont` scans the build's emitted assets after
//      the bundle is rendered and injects a `<link rel="preload"
//      as="font">` into `index.html` for the Latin subset of the
//      body font (Outfit) — the family hit on every paint, and the
//      `unicode-range` partition French and English visitors
//      actually need. Vite hashes the asset filename per build, so
//      the lookup happens at bundle time rather than being hard-coded.
//      This kicks the woff2 fetch off in the preload scanner phase
//      rather than waiting for CSS parse + `@font-face` discovery,
//      shaving the visible-flicker window. Heading copy uses
//      Fraunces and falls back via fontaine until that woff2 arrives;
//      preloading body is the higher-leverage of the two on every
//      route except `/` and `/grilles` where headings dominate above
//      the fold.
//
// Both pieces are no-ops in dev (the dev server serves CSS/woff2
// straight from `node_modules`); they only run during `vite build`.
function preloadLatinBodyFont(): Plugin {
  return {
    name: 'preload-latin-body-font',
    apply: 'build',
    enforce: 'post',
    transformIndexHtml: {
      order: 'post',
      handler(html, ctx) {
        const bundle = ctx.bundle;
        if (!bundle) return html;
        const latin = Object.keys(bundle).find(
          (fileName) =>
            fileName.includes('outfit-latin-wght-normal') &&
            fileName.endsWith('.woff2'),
        );
        if (!latin) return html;
        const href = `/${latin}`;
        return {
          html,
          tags: [
            {
              tag: 'link',
              attrs: {
                rel: 'preload',
                href,
                as: 'font',
                type: 'font/woff2',
                crossorigin: '',
              },
              injectTo: 'head-prepend',
            },
          ],
        };
      },
    },
  };
}

// MSW preview handlers (see ADR-0007 §5) replay the spec's
// `examples/` payloads so the preview SPA stays contract-conformant
// without a live API. The fixtures live in `grid/api/examples/` —
// outside Vite's `root` and outside `frontend/`'s tsconfig include.
// Rather than copy the file or relax `resolveJsonModule`, this
// plugin exposes each example as a virtual ESM module:
//
//   import puzzle from 'virtual:grid-api-examples/get-puzzle-200';
//
// The JSON is read from disk at resolve time, so the spec is the
// single source of truth — no committed copy to drift.
function gridApiExamplesAsVirtualModule(): Plugin {
  const prefix = 'virtual:grid-api-examples/';
  const examplesDir = path.resolve(__dirname, '../grid/api/examples');
  return {
    name: 'grid-api-examples-virtual',
    resolveId(id) {
      if (id.startsWith(prefix)) return '\0' + id;
    },
    load(resolved) {
      if (!resolved.startsWith('\0' + prefix)) return;
      const name = resolved.slice(('\0' + prefix).length);
      const file = path.join(examplesDir, `${name}.json`);
      const json = readFileSync(file, 'utf8');
      return `export default ${json};`;
    },
  };
}

export default defineConfig({
  plugins: [
    react(),
    // Metrics-matched fallback face: rewrites the local
    // `@font-face` rules in `src/ui/styles/fonts.css` so the system
    // fallback renders at each web font's exact metrics before the
    // woff2 arrives. Eliminates the reflow on swap.
    //
    // Fontaine auto-detects font families from the CSS — currently
    // Fraunces Variable (heading) and Outfit Variable (body) per
    // ADR-0043 §3. Lekton is monospace and only used inside def
    // cells, which are sized rigidly by the grid algorithm; a
    // fallback face for it would never visibly help, so it's not
    // worth the extra emitted CSS.
    //
    // Fontaine only generates a fallback face when it has metrics for
    // *both* the source family and the fallback family. The fallbacks
    // listed here all have metrics in fontaine's bundled capsize
    // database (`BlinkMacSystemFont`, `Segoe UI`, `Roboto`, `Arial`,
    // `Georgia`, `Times New Roman`); generic CSS keywords like
    // `system-ui` / `sans-serif` / `serif` have no metrics and would
    // silently no-op, so they live only in the Panda font-stack
    // tokens (see `panda.config.ts`) as the ultimate hard fallback
    // when even the metrics-matched face can't load. Georgia + Times
    // are listed alongside the sans fallbacks so fontaine can pick
    // an appropriate metrics source for Fraunces (serif) without
    // needing a per-family config.
    FontaineTransform.vite({
      fallbacks: [
        'BlinkMacSystemFont',
        'Segoe UI',
        'Roboto',
        'Arial',
        'Georgia',
        'Times New Roman',
      ],
      // Fontaine can read metrics from a relative `./` or `../` path
      // (resolved against the importing CSS file) or from an absolute
      // URL. The `@font-face` `src: url(...)` declarations in
      // `src/ui/styles/fonts.css` use bare-module specifiers
      // (`@fontsource-variable/fraunces/files/...`,
      // `@fontsource-variable/outfit/files/...`) so Vite's asset
      // pipeline can hash and emit them; fontaine doesn't know how
      // to resolve those, so this hook maps the bare specifier to the
      // package's location under `node_modules/` for the metrics read.
      resolvePath: (id) =>
        pathToFileURL(path.resolve(__dirname, 'node_modules', id)),
    }),
    preloadLatinBodyFont(),
    gridApiExamplesAsVirtualModule(),
    // PWA + offline cache. Workbox precaches the app shell so a reload
    // works without network, and applies a NetworkFirst strategy to the
    // grid API so the last-loaded puzzle stays playable offline. The
    // existing `manifest.webmanifest` is the source of truth — we set
    // `manifest: false` so the plugin does not generate a competing one.
    // `registerType: 'autoUpdate'` flips active SWs as soon as a new
    // version is precached; the `pwa.ts` adapter wraps registration so
    // the rest of the app stays unaware.
    VitePWA({
      registerType: 'autoUpdate',
      injectRegister: false,
      filename: 'sw.js',
      manifest: false,
      includeAssets: [
        'favicon.svg',
        'icon-180.png',
        'icon-192.png',
        'icon-512.png',
        'manifest.webmanifest',
      ],
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,png,woff2,webmanifest}'],
        navigateFallback: '/index.html',
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
        cleanupOutdatedCaches: true,
        // skipWaiting+clientsClaim activate the new SW on install and
        // adopt all open tabs immediately; pwa.ts's `controlling` handler
        // then reloads each tab onto the new build. A still-open old page
        // that lazy-imports a now-vanished route chunk gets a 404 (top-
        // level 404.html, no SPA fallback) → vite:preloadError, which
        // pwa.ts's chunk-mismatch guard recovers from by updating the SW
        // and reloading onto the fresh shell + precache.
        skipWaiting: true,
        clientsClaim: true,
        runtimeCaching: [
          {
            // Grid API: serve fresh when online, fall back to cache when
            // offline. 5s network timeout means a flaky link reverts to
            // the cached puzzle quickly. 1-week TTL is well under any
            // realistic puzzle-content rotation.
            urlPattern: ({ url }) =>
              url.hostname === 'api.wordsparrow.io' &&
              url.pathname.startsWith('/v1/puzzles/'),
            handler: 'NetworkFirst',
            options: {
              cacheName: 'grid-api-puzzles',
              networkTimeoutSeconds: 5,
              expiration: {
                maxEntries: 32,
                maxAgeSeconds: 7 * 24 * 60 * 60,
              },
              cacheableResponse: { statuses: [0, 200] },
            },
          },
        ],
      },
      devOptions: {
        enabled: false,
      },
    }),
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
      'styled-system': path.resolve(__dirname, './styled-system'),
    },
  },
  build: {
    // Public maps are fine because the repo is public on GitHub
    // (the OCI `org.opencontainers.image.source` label baked into
    // our Dockerfiles confirms it). Map files add zero source
    // disclosure that isn't already at `Ishou/bliss`. ~250 KB per
    // asset on the static host; CDN-cacheable, only fetched on demand.
    //
    // If the repo ever flips private, change `true` → `'hidden'` and
    // add a CI step that uploads the `.map` files to a private bucket;
    // SigNoz still won't auto-unmap, but a developer with the maps in
    // hand can.
    sourcemap: true,
    rollupOptions: {
      output: {
        // Manual vendor splits. Goal: keep stable third-party code in
        // its own long-lived chunks so a deploy of app-only changes
        // doesn't bust the user's React/Router/OTel cache. Trade-off:
        // 3 extra HTTP requests on first paint, but each ~30-100 KB
        // gzipped over HTTP/2 multiplexing is negligible against the
        // cache-hit rate on returning visits. Without these, every
        // app change reinvalidates the entire 240 KB gzipped bundle.
        //
        // Only split vendors that are (a) large enough to matter,
        // (b) genuinely stable across most deploys, and (c) loaded
        // on every route. Per-route code stays in the default chunk
        // — Rollup's automatic splitting handles dynamic imports if
        // we add them later.
        manualChunks: (id) => {
          if (
            id.includes('/node_modules/react/') ||
            id.includes('/node_modules/react-dom/') ||
            id.includes('/node_modules/scheduler/')
          ) {
            return 'vendor-react';
          }
          if (id.includes('/node_modules/@tanstack/')) {
            return 'vendor-router';
          }
          if (id.includes('/node_modules/@opentelemetry/')) {
            return 'vendor-otel';
          }
          // Default — everything else stays with the app bundle. Ark
          // UI (~150 KB) is intentionally NOT split: it's used on
          // nearly every route, would invalidate alongside app code
          // on most deploys anyway, and a separate chunk just adds a
          // round-trip without a caching win.
          return undefined;
        },
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./vitest.setup.ts'],
    css: false,
    // Vitest's default discovery globs `**/*.{test,spec}.ts`, which
    // would pick up `frontend/e2e/*.spec.ts` (Playwright tests, no
    // jsdom, no vitest globals). Exclude e2e/ — Playwright's own
    // runner handles them via `pnpm e2e`.
    exclude: ['**/node_modules/**', '**/dist/**', 'e2e/**'],
  },
});
