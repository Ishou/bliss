// Service-worker registration for the PWA + offline cache.
//
// `vite-plugin-pwa` generates the actual `/sw.js` from the workbox
// config in `vite.config.ts` (precaches the app shell, NetworkFirst for
// puzzle GETs). This module wires browser registration via the
// workbox-window helper, which fits the plugin's `registerType:
// 'autoUpdate'` mode: a freshly precached SW activates on the next
// page load without prompting.
//
// Skipped in dev (`import.meta.env.DEV`) so HMR isn't shadowed by a
// cached shell.

import { Workbox } from 'workbox-window';

// Window after `load` during which a `controlling` event is treated as
// "F5 just landed on a deploy boundary" and the page reloads
// immediately. After this window we assume the user is mid-session and
// defer the reload to the next time the tab regains visibility — that
// keeps a long-lived second tab from being yanked out from under the
// user when another tab triggers an update.
const FRESH_LOAD_RELOAD_WINDOW_MS = 3000;

export function registerServiceWorker(): void {
  if (typeof window === 'undefined') return;
  if (!('serviceWorker' in navigator)) return;
  if (import.meta.env.DEV) return;
  // Preview mode runs MSW's own service worker at scope `/`. Registering
  // workbox here would override it (same scope, last registration
  // wins), and the preview deploy would stop replaying the OpenAPI
  // example fixtures. Skip in mock mode — production builds set
  // VITE_USE_MOCK_API=false so this gate is open there.
  if (import.meta.env.VITE_USE_MOCK_API === 'true') return;

  const start = () => {
    // `updateViaCache: 'none'` forces the browser to fetch `/sw.js`
    // from the network on every registration call, instead of trusting
    // its HTTP cache for up to 24 h. Without this, a normal F5 can
    // serve a stale SW and miss a deploy until the user does Ctrl+F5
    // — the symptom that motivated this module's last revision.
    const wb = new Workbox('/sw.js', { updateViaCache: 'none' });
    const loadAt = performance.now();
    let refreshing = false;
    let staleVisibilityListener: (() => void) | null = null;

    const reloadOnce = () => {
      if (refreshing) return;
      refreshing = true;
      window.location.reload();
    };

    const armDeferredReload = () => {
      if (staleVisibilityListener) return;
      staleVisibilityListener = () => {
        if (document.visibilityState === 'visible') reloadOnce();
      };
      document.addEventListener('visibilitychange', staleVisibilityListener);
    };

    // `controlling` fires when the controller for this client changes.
    // For a brand-new first install (no previous controller),
    // workbox-window suppresses it, so this only triggers on real
    // updates.
    //
    // Two regimes:
    // - Fresh load (within FRESH_LOAD_RELOAD_WINDOW_MS of `load`): the
    //   user just refreshed; reload synchronously so they end on the
    //   new build without a perceivable double-render and without
    //   needing a hard refresh.
    // - Mid-session: defer until the tab next becomes visible. This
    //   avoids reloading a tab the user is actively typing into when
    //   another tab races ahead to a new deploy.
    wb.addEventListener('controlling', () => {
      const elapsedMs = performance.now() - loadAt;
      const visible = document.visibilityState === 'visible';
      if (elapsedMs < FRESH_LOAD_RELOAD_WINDOW_MS && visible) {
        reloadOnce();
        return;
      }
      armDeferredReload();
    });

    wb.register().catch((err: unknown) => {
      // Registration failures are non-fatal — the app still works
      // online; only the offline-shell precache is lost.
      console.warn('SW registration failed', err);
    });
  };

  // `registerServiceWorker` is called from `main.tsx` after async MSW
  // init resolves, which often lands after `window`'s `load` event has
  // already fired. `addEventListener('load', ...)` does not fire
  // retroactively, so we branch on `document.readyState` to register
  // synchronously when we've missed the boat.
  if (document.readyState === 'complete') {
    start();
  } else {
    window.addEventListener('load', start, { once: true });
  }
}
