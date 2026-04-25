# ADR-0007: Font FOUT Mitigation (Preload + Metrics-Matched Fallback)

## Status

Accepted

## Context

After ADR-0005 committed to Nunito Variable self-hosted via the build pipeline,
the production site exhibited a Flash of Unstyled Text (FOUT) on every cold
page load. Definition-cell text (e.g., "Astre nocturne") wrapped onto two lines
on first paint, then reflowed to one line ~100–300 ms later when the Nunito
woff2 arrived. The root cause is that system-font fallbacks (`system-ui`,
`Segoe UI`, `Arial`, etc.) have different glyph widths than Nunito at the same
`font-size`, so the same text string occupies different pixel widths in the
fallback face vs. the brand face. The reflow is user-visible as a layout shift.

Options considered:

1. **`font-display: optional`** — instructs the browser to skip Nunito
   entirely on slow connections if the woff2 has not arrived by the first
   render. Eliminates flicker, but the font is simply hidden, which
   contradicts ADR-0005 §5's commitment to ship Nunito as the brand face.
   Rejected.

2. **`font-display: block`** — renders invisible text until Nunito loads.
   Eliminates reflow but replaces it with invisible (blank) text for
   hundreds of milliseconds on slow connections. Worse UX than FOUT.
   Rejected.

3. **Preload only** — injecting `<link rel="preload" as="font">` for the
   Latin woff2 subset moves the fetch earlier (preload-scanner phase rather
   than CSS-parse + `@font-face` discovery), which shortens the FOUT window.
   Reduces flicker but does not eliminate it on slow connections. Partial
   solution; combined with option 4.

4. **Metrics-matched fallback face via `fontaine`** — `fontaine` reads
   Nunito Variable's actual vertical metrics (`ascent`, `descent`,
   `line-gap`) and average glyph width (`xWidthAvg`) from the woff2 in
   `node_modules`, then emits a sibling `@font-face` for each system fallback
   (`BlinkMacSystemFont`, `Segoe UI`, `Roboto`, `Arial`) with
   `size-adjust`, `ascent-override`, `descent-override`, and
   `line-gap-override` values calculated so the fallback face occupies the
   same pixel footprint as Nunito. When Nunito swaps in, the layout does not
   shift — there is nothing to reflow. Solves the problem completely.

Options 3 and 4 are complementary: preload shortens the window before Nunito
arrives; the metrics-matched fallback ensures zero reflow when it does arrive.
Both ship together.

`fontaine` is a Vite/webpack plugin authored by the Nuxt team and published on
npm under the MIT license. It operates entirely at build time (devDependency
only; zero runtime footprint). The generated `@font-face` rules land in the
emitted CSS bundle, not in any runtime module.

## Decision

Ship three coupled changes in one PR:

1. **Preload injection via Vite plugin.** A small `preloadLatinNunito` plugin
   (defined in `vite.config.ts`) reads the emitted asset manifest at
   `generateBundle` time and injects
   `<link rel="preload" as="font" type="font/woff2" crossorigin="">` for the
   Latin variable-weight normal woff2 into `index.html`. The asset path is
   resolved from Vite's per-build content hash at bundle time, so it is always
   correct without manual maintenance.

2. **Metrics-matched fallback via `fontaine`.** `fontaine` is added as a
   devDependency and wired as the first Vite plugin in `vite.config.ts`. It
   reads Nunito Variable metrics from `node_modules` and emits a sibling
   `@font-face` named `"Nunito Variable fallback"` for each matched system font
   with computed override values.

3. **Panda font-stack update.** `panda.config.ts` `body` and `heading` tokens
   insert `"Nunito Variable fallback"` between `"Nunito Variable"` and the
   existing `system-ui, …, sans-serif` chain so the browser actually picks
   up the metrics-matched face during the FOUT window.

**Side-effect: `fonts.css` import path.** Fontaine's transform hook runs
`enforce: 'pre'`, but Vite's CSS pipeline resolves `@import` after that hook —
so a CSS-side `@import '@fontsource-variable/nunito/index.css'` would hide the
`@font-face` declarations from fontaine and no fallback face would be generated.
To work around this, the `@font-face` declarations are declared in
`src/ui/styles/fonts.css` (self-hosted — `url()` references resolve to
fontsource's woff2 files via Vite's asset pipeline, no Google Fonts CDN call,
ADR-0005 §5 preserved) and `fonts.css` is imported from `main.tsx` rather than
via `@import` from `index.css`.

`font-display` stays at `swap` (fontsource's default and the right choice for
this use case). `optional` was explicitly rejected — see Context above.

Bundle delta (gzipped, initial route): HTML +0.06 KB, CSS +0.26 KB, JS 0.
Total +0.32 KB, well within the 200 KB budget established in ADR-0002.

## Consequences

### Easier

- Definition-cell text (and all other text using the Nunito font stack) renders
  at the correct pixel width from first paint, so Nunito swap-in is visually
  invisible. FOUT is eliminated on all connection speeds.
- The preload tag instructs the browser's preload-scanner to fetch the Latin
  woff2 in parallel with HTML parse rather than waiting for CSS parse +
  `@font-face` discovery, which shortens time-to-brand-font on fast connections.
- `fontaine`'s generated `@font-face` rules are static CSS in the emitted
  bundle — zero JavaScript runtime cost.

### Harder

- `fontaine` reads metrics from the woff2 path it discovers in `node_modules`.
  If `@fontsource-variable/nunito` changes its package layout (e.g., renames
  the woff2 directory), fontaine may silently skip fallback generation. The
  mitigation is the existing `pnpm build` check (step 4 in the PR test plan)
  that verifies `size-adjust` / `ascent-override` blocks are present in the
  emitted CSS.
- The `preloadLatinNunito` plugin depends on a filename convention
  (`nunito-latin-wght-normal-`) to locate the right woff2 in the bundle. If
  fontsource renames the file, the preload tag is silently omitted. Same
  mitigation: build-time assertion on the emitted `index.html`.

### Different

- `@font-face` declarations for Nunito move from a CSS `@import` chain into
  `src/ui/styles/fonts.css` imported from `main.tsx`. This is a cosmetic
  restructuring of the import graph; the self-hosting contract from ADR-0005 §5
  is unchanged.
- `fontaine` is a new devDependency. It has zero runtime footprint and does not
  appear in the production bundle.
