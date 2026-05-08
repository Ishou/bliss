// Browser-side MSW worker factory.
//
// Imported dynamically from `src/main.tsx` only when at least one of
// the per-surface mock flags is on (`VITE_MOCK_GRID_API` or
// `VITE_MOCK_GAME_API`), so the `msw/browser` chunk and every
// transitive handler ship only with builds that opt into mocking.
// Production builds set both flags false and the dynamic import is
// dead code under falsy literal env values, so Vite tree-shakes
// `msw/browser` and every handler out. See ADR-0007 §5 for the
// posture and `vite.config.ts` for the virtual-module fixture loader.

import { setupWorker, type SetupWorker } from 'msw/browser';
import type { RequestHandler, WebSocketHandler } from 'msw';

export type AnyHandler = RequestHandler | WebSocketHandler;

export function createWorker(handlers: readonly AnyHandler[]): SetupWorker {
  return setupWorker(...handlers);
}
