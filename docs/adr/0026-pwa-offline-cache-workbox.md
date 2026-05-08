# ADR-0026: PWA Offline Cache — vite-plugin-pwa + Workbox

## Status

Accepted

## Context

ADR-0002 §5 commits Bliss to shipping as a Progressive Web App: installable on
iOS, Android, and desktop, with offline-capable service-worker caching for
puzzles and assets. The v1 codebase had a stub `frontend/public/sw.js` that
registered an empty install/activate cycle — enough for DevTools to light up
"installable", but leaving the page hanging the moment the network dropped.

Two dependencies and a build-system integration are needed to fulfil the
ADR-0002 commitment:

- **A service-worker generator** that produces a precache manifest from the
  Vite build's emitted asset graph, so the cached shell stays coherent with
  hashed filenames across builds.
- **A runtime-caching strategy** for the Grid API (`/v1/puzzles/*`) that
  serves the last-loaded puzzle when the device goes offline.
- **A client-side registration helper** that handles the `autoUpdate` lifecycle
  (swap in a new SW as soon as it is precached, no user prompt) without
  cluttering the application layer.

Options considered:

| Option | Notes |
|---|---|
| Hand-rolled SW (`public/sw.js` authored manually) | Full control, but precache manifest must be maintained by hand or via a separate Workbox CLI step outside Vite's pipeline. Fragile: asset hashes change every build. |
| `vite-plugin-pwa` + Workbox | Generates `sw.js` from the build's emitted asset list. First-class Vite plugin; no separate build step. Workbox is the industry-standard SW abstraction (used by Angular, CRA, Next.js). |
| `workbox-build` as a post-build script | Achieves the same result but requires a custom glue script and runs outside Vite's lifecycle, making it harder to test and more error-prone. |

`vite-plugin-pwa` is the only option that integrates cleanly into the existing
Vite pipeline and generates a correct precache manifest without a manual step.

An additional conflict exists in preview deployments: MSW registers its own
service worker at scope `/` to replay OpenAPI example fixtures. A workbox SW
registered at the same scope would win (last registration wins) and suppress
MSW, breaking preview contract-conformance. A guard in `pwa.ts` detects
`VITE_USE_MOCK_API=true` and skips registration entirely in that environment.

## Decision

Add `vite-plugin-pwa` and `workbox-window` as production dependencies.

Configure `VitePWA` in `vite.config.ts` with:

- `registerType: 'autoUpdate'` — new precache manifests activate on next
  page load without a user prompt, consistent with the app's silent-update UX
  and the fact that puzzle content does not carry unsaved local state.
- `injectRegister: false` — registration is handled explicitly in
  `infrastructure/pwa.ts` via `workbox-window`, giving the application layer
  control over the timing and guard logic.
- `manifest: false` — the existing `manifest.webmanifest` committed under
  `public/` is the single source of truth; the plugin must not generate a
  competing one.
- Precache glob: `**/*.{js,css,html,svg,png,woff2,webmanifest}` — captures
  the full app shell including fonts and icons.
- `navigateFallback: '/index.html'` with `/^\/v1\//` in
  `navigateFallbackDenylist` — SPA navigation works offline; API paths are
  never intercepted by the navigation fallback.
- `cleanupOutdatedCaches: true` — stale precaches from previous builds are
  deleted automatically.

Runtime caching for the Grid API:

```
urlPattern: hostname === 'api.wordsparrow.io' && pathname.startsWith('/v1/puzzles/')
handler:    NetworkFirst
cacheName:  grid-api-puzzles
networkTimeoutSeconds: 5
maxEntries: 32
maxAgeSeconds: 604 800  (1 week)
cacheableResponse: { statuses: [0, 200] }
```

Rationale for each parameter:

- **NetworkFirst** (not CacheFirst): puzzle content may be updated server-side
  (corrections, daily rotation). The player always gets the freshest puzzle
  when online; the offline copy is a fallback, not the primary path.
- **5 s network timeout**: a flaky mobile connection should revert to the
  cached puzzle quickly rather than spinning indefinitely. 5 s is long enough
  to succeed on a slow but functional connection and short enough to feel
  intentional on a dead one.
- **32 entries**: one entry per unique puzzle URL. A player who visits 32
  distinct puzzles before going offline has all of them available; older
  entries are evicted. This bounds cache growth without meaningful UX impact.
- **1-week TTL**: well under any realistic puzzle-content rotation cycle.
  Ensures the cache self-heals even if a player never clears storage manually.
- **`statuses: [0, 200]`**: status 0 covers opaque responses from cross-origin
  fetches; including it means the offline copy is usable even if the initial
  cache fill happened via a navigation fetch rather than an XHR.

The stub `frontend/public/sw.js` is deleted; `vite build` generates the real
one at the same path.

`infrastructure/pwa.ts` wraps registration with three guards:
1. Skip on non-browser environments (SSR / test).
2. Skip if the browser has no `serviceWorker` support.
3. Skip in dev (`import.meta.env.DEV`) so HMR is not shadowed.
4. Skip when `VITE_USE_MOCK_API=true` (preview/MSW mode) to avoid overriding
   MSW's SW at scope `/`.

## Consequences

### Easier

- Offline play is fully functional: the app shell loads from the precache and
  the last-fetched puzzle is served from the runtime cache.
- The precache manifest is automatically correct across builds — no manual
  maintenance, no stale-asset risk from hashed filenames.
- `autoUpdate` means players on the previous version are silently upgraded on
  the next page load, with no "Update available" prompt to design or
  maintain.
- MSW preview deployments are unaffected: the guard in `pwa.ts` prevents the
  workbox SW from competing with MSW at scope `/`.

### Harder

- The precache manifest is generated at build time and tied to each specific
  production build. A rollback must re-deploy the previous build artifact,
  not just revert a file — already the case under the manifesto's immutable
  deployment model.
- `autoUpdate` means there is no user-visible "a new version is ready" moment.
  If a future feature requires a coordinated client-side migration (e.g. a
  breaking localStorage schema change), `registerType` must be changed to
  `'prompt'` and a UI notification added. Acceptable: no such migration is
  planned for v1.
- Two new packages enter the dependency tree. `workbox-*` is maintained by
  Google and is a transitive dependency of many major frameworks; the supply
  chain risk is low. `vite-plugin-pwa` is a single-maintainer library;
  security scanning (already in CI per the manifesto) provides the ongoing
  gate.

### Different

- `public/sw.js` is no longer a source file — it is a build artifact.
  Developers must run `vite build` (or inspect the preview server) to see the
  actual service-worker output; the source of truth is `vite.config.ts`.
