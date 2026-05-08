// Cookieless Matomo tracker for the Bliss frontend (ADR-0025 §2 / §3).
//
// Loads `matomo.js` from the self-hosted analytics server, configures the
// CNIL audience-measurement consent-exemption posture, and exposes a small
// typed surface for page views and custom events. The companion server-side
// adapter lives in `:grid:infrastructure` / `:game:infrastructure`.
//
// Configuration is **opt-in via env vars** (`VITE_MATOMO_URL`,
// `VITE_MATOMO_SITE_ID`). Missing or empty → tracker is a no-op so dev /
// MSW preview / pre-Matomo prod work unchanged.

declare global {
  interface Window {
    _paq?: unknown[];
  }
}

export interface MatomoTrackerConfig {
  /** Base URL of the Matomo server, e.g. `https://analytics.wordsparrow.io`. */
  readonly url: string;
  /** Numeric site id assigned by the Matomo first-run wizard (Bliss = `1`). */
  readonly siteId: string;
}

export interface MatomoTracker {
  trackPageView(url: string, title?: string): void;
  trackEvent(category: string, action: string, name?: string, value?: number): void;
}

/** No-op fallback when env vars are missing or in non-prod environments. */
const noopTracker: MatomoTracker = {
  trackPageView() {
    /* noop */
  },
  trackEvent() {
    /* noop */
  },
};

/**
 * Initialise the Matomo tracker and inject the `matomo.js` script. Returns a
 * tracker handle for programmatic page-view + custom-event reporting. Calling
 * twice is safe — the first call wins; subsequent calls return the existing
 * tracker without re-injecting.
 *
 * The CNIL-aligned configuration applied here (load-bearing for the consent
 * exemption per ADR-0025 §2):
 * - `disableCookies` — no client-side identifier persists.
 * - `setDoNotTrack` — honours the browser's `Do Not Track` header.
 * - `enableHeartBeatTimer(15)` — refresh the visit window every 15s of
 *   actual page activity. Doesn't introduce any persistent state; just
 *   prevents the visit being closed prematurely on long grids.
 */
export function createMatomoTracker(config: MatomoTrackerConfig | null): MatomoTracker {
  if (!config) return noopTracker;
  const { url, siteId } = config;
  if (typeof window === 'undefined') return noopTracker;

  const paq = (window._paq = window._paq ?? []);
  const trackerUrl = url.replace(/\/$/, '') + '/matomo.php';
  const scriptUrl = url.replace(/\/$/, '') + '/matomo.js';

  // Load order matters — Matomo's queued helper expects these calls before
  // the script tag is injected.
  paq.push(['disableCookies']);
  paq.push(['setDoNotTrack', true]);
  paq.push(['setTrackerUrl', trackerUrl]);
  paq.push(['setSiteId', siteId]);
  paq.push(['enableHeartBeatTimer', 15]);
  paq.push(['enableLinkTracking']);

  // Inject the script async so it doesn't block the SPA boot.
  const existing = document.querySelector(`script[data-matomo="${siteId}"]`);
  if (!existing) {
    const script = document.createElement('script');
    script.async = true;
    script.defer = true;
    script.src = scriptUrl;
    script.dataset.matomo = siteId;
    document.head.appendChild(script);
  }

  return {
    trackPageView(routeUrl, title) {
      paq.push(['setCustomUrl', routeUrl]);
      if (title) paq.push(['setDocumentTitle', title]);
      paq.push(['trackPageView']);
    },
    trackEvent(category, action, name, value) {
      const args: Array<unknown> = ['trackEvent', category, action];
      if (name !== undefined) args.push(name);
      if (value !== undefined) args.push(value);
      paq.push(args);
    },
  };
}

/**
 * Read tracker config from Vite env vars. Returns `null` when either var is
 * missing or empty — caller wires the noop tracker in that case.
 */
export function readMatomoConfigFromEnv(): MatomoTrackerConfig | null {
  const url = import.meta.env.VITE_MATOMO_URL;
  const siteId = import.meta.env.VITE_MATOMO_SITE_ID;
  if (!url || !siteId) return null;
  return { url, siteId };
}
