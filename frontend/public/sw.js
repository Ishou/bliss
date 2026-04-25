// Bliss service worker stub. ADR-0002 §5 commits to a PWA in v1, but
// full offline caching and Web Push are deferred. This file exists so
// `navigator.serviceWorker.register('/sw.js')` succeeds; the install/
// activate handlers no-op until a caching strategy is decided.

self.addEventListener('install', () => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim());
});
