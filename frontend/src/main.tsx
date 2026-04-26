// Composition root for the Bliss frontend bundle. This file is the only
// place where the ui and infrastructure layers are wired together; it is
// excluded from the layered architecture rules in eslint.config.js.
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from '@/ui/App';
import { createAppRouter } from '@/ui/router';
import { createHttpPuzzleRepository } from '@/infrastructure';
import { registerServiceWorker } from '@/infrastructure/pwa';
// `fonts.css` is imported separately (rather than via `@import` from
// `index.css`) so the `@font-face` rules reach the `fontaine` Vite
// plugin's `transform` hook directly. CSS-side `@import` is resolved
// after that hook runs, which would hide the rules from fontaine and
// no metrics-matched fallback face would be generated. See
// `vite.config.ts` and `src/ui/styles/fonts.css` for the rationale.
import '@/ui/styles/fonts.css';
import '@/ui/styles/index.css';

// MSW preview-mode bootstrap (ADR-0007 §5). Cloudflare Pages preview
// builds set `VITE_USE_MOCK_API=true` via `.env.preview`; production
// builds load `.env` (`VITE_USE_MOCK_API=false`) and the dynamic import
// below is dead code, so Vite tree-shakes `msw/browser` and every
// handler out of the prod bundle. Verified with:
//
//   pnpm build && grep -r setupWorker dist/   # → empty
//
// The promise-chained app bootstrap ensures the service worker is
// active *before* React renders, so the very first XHR from the
// router loader is intercepted (avoids a race where the initial
// fetch slips through to the real API host).
async function enableMocks(): Promise<void> {
  if (import.meta.env.VITE_USE_MOCK_API !== 'true') return;
  const { worker } = await import('@/infrastructure/mocks/browser');
  await worker.start({
    serviceWorker: { url: '/mockServiceWorker.js' },
    onUnhandledRequest: 'bypass',
  });
}

const container = document.getElementById('root');
if (!container) {
  throw new Error('Root container #root not found in index.html');
}

enableMocks()
  .catch((err: unknown) => {
    console.error('[MSW] worker failed to start, continuing without mock:', err);
  })
  .then(() => {
    const puzzleRepository = createHttpPuzzleRepository({
      baseUrl: import.meta.env.VITE_GRID_API_URL,
    });
    const router = createAppRouter({ puzzleRepository });

    createRoot(container).render(
      <StrictMode>
        <App router={router} />
      </StrictMode>,
    );

    registerServiceWorker();
  });
