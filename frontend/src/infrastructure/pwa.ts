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

  window.addEventListener('load', () => {
    const wb = new Workbox('/sw.js');

    // When a new SW takes control of this client, the precached files
    // (index.html, JS bundle, CSS) are now the new build's. The
    // currently-running JS bundle in this tab is still the OLD one,
    // though, so any client-side route added in the new build won't
    // resolve — TanStack Router renders the 404 component for
    // navigations that match the old route table. The user's
    // workaround is a hard refresh; we automate it.
    //
    // `controlling` fires when the controller changes. For a brand-new
    // first install (no previous controller), workbox-window doesn't
    // emit it, so this only triggers on real updates. The `refreshing`
    // guard prevents a reload loop if multiple `controlling` events
    // fire (rare but defensible — Chrome can fire twice during a fast
    // update).
    let refreshing = false;
    wb.addEventListener('controlling', () => {
      if (refreshing) return;
      refreshing = true;
      window.location.reload();
    });

    wb.register().catch((err: unknown) => {
      // Registration failures are non-fatal — the app still works
      // online; only the offline-shell precache is lost.
      console.warn('SW registration failed', err);
    });
  });
}
