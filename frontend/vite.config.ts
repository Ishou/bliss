import { defineConfig, type Plugin } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { FontaineTransform } from 'fontaine';

// Vite + React 19 config for the Bliss frontend bounded context.
// See ADR-0002 for the stack rationale.
//
// Two FOUT-mitigation pieces are wired here (see PR
// `fix/frontend-font-flicker`):
//
//   1. `FontaineTransform` rewrites every `@font-face` rule that
//      fontsource emits to add a sibling face named
//      `"Nunito Variable fallback"` whose `size-adjust`,
//      `ascent-override`, `descent-override`, and `line-gap-override`
//      make the system fallback occupy the same pixels as Nunito.
//      Result: no reflow when the woff2 swaps in.
//
//   2. `preloadLatinNunito` scans the build's emitted assets after the
//      bundle is rendered and injects a `<link rel="preload" as="font">`
//      into `index.html` for the Latin subset only (the unicode-range
//      French and English visitors actually need). Vite hashes the asset
//      filename per build, so the lookup happens at bundle time rather
//      than being hard-coded. This kicks the woff2 fetch off in the
//      preload scanner phase rather than waiting for CSS parse +
//      `@font-face` discovery, shaving the visible-flicker window.
//
// Both pieces are no-ops in dev (the dev server serves CSS/woff2
// straight from `node_modules`); they only run during `vite build`.
function preloadLatinNunito(): Plugin {
  return {
    name: 'preload-latin-nunito',
    apply: 'build',
    enforce: 'post',
    transformIndexHtml: {
      order: 'post',
      handler(html, ctx) {
        const bundle = ctx.bundle;
        if (!bundle) return html;
        const latin = Object.keys(bundle).find(
          (fileName) =>
            fileName.includes('nunito-latin-wght-normal') &&
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
    // fallback renders at Nunito's exact metrics before the woff2
    // arrives. Eliminates the reflow on swap.
    //
    // Fontaine only generates a fallback face when it has metrics for
    // *both* the source family and the fallback family. The fallbacks
    // listed here all have metrics in fontaine's bundled capsize
    // database (`BlinkMacSystemFont`, `Segoe UI`, `Roboto`, `Arial`);
    // generic CSS keywords like `system-ui` / `sans-serif` have no
    // metrics and would silently no-op, so they live only in the
    // Panda font-stack token (see `panda.config.ts`) as the ultimate
    // hard fallback when even the metrics-matched face can't load.
    FontaineTransform.vite({
      fallbacks: ['BlinkMacSystemFont', 'Segoe UI', 'Roboto', 'Arial'],
      // Fontaine can read metrics from a relative `./` or `../` path
      // (resolved against the importing CSS file) or from an absolute
      // URL. The `@font-face` `src: url(...)` declarations in
      // `src/ui/styles/fonts.css` use bare-module specifiers
      // (`@fontsource-variable/nunito/files/...`) so Vite's asset
      // pipeline can hash and emit them; fontaine doesn't know how
      // to resolve those, so this hook maps the bare specifier to the
      // package's location under `node_modules/` for the metrics read.
      resolvePath: (id) =>
        pathToFileURL(path.resolve(__dirname, 'node_modules', id)),
    }),
    preloadLatinNunito(),
    gridApiExamplesAsVirtualModule(),
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
      'styled-system': path.resolve(__dirname, './styled-system'),
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./vitest.setup.ts'],
    css: false,
  },
});
