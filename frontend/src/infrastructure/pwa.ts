// Service-worker registration. Per ADR-0002 §5, the v1 PWA scaffold
// registers a no-op service worker so installability lights up; full
// offline caching and Web Push come in a follow-up workstream.

export function registerServiceWorker(): void {
  if (typeof window === 'undefined') return;
  if (!('serviceWorker' in navigator)) return;
  if (import.meta.env.DEV) return;

  window.addEventListener('load', () => {
    void navigator.serviceWorker.register('/sw.js').catch((err: unknown) => {
      // Registration failures are non-fatal for v1. We warn pending the
      // telemetry adapter (manifesto's "no console" rule targets production
      // logging; SW registration warnings are dev-friendly diagnostics
      // until the observability ADR ships).
      console.warn('SW registration failed', err);
    });
  });
}
