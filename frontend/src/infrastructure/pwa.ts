// Service-worker registration. Per ADR-0002 §5, the v1 PWA scaffold
// registers a no-op service worker so installability lights up; full
// offline caching and Web Push come in a follow-up workstream.

export function registerServiceWorker(): void {
  if (typeof window === 'undefined') return;
  if (!('serviceWorker' in navigator)) return;
  if (import.meta.env.DEV) return;

  window.addEventListener('load', () => {
    void navigator.serviceWorker.register('/sw.js').catch(() => {
      // Registration failures are non-fatal for v1. Telemetry adapter
      // will report these once the observability ADR lands.
    });
  });
}
