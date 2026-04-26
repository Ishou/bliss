// MSW request handlers for Cloudflare Pages preview deploys.
//
// Per ADR-0007 §5 and ADR-0009 §7, per-PR previews stay frontend-only:
// the real Grid API runs only on `main`, so previews would otherwise
// either point at production (data-leak risk + noise on real metrics)
// or 404. These handlers intercept every Grid API call in the browser
// and replay the spec's `examples/` payloads — exactly the contract
// the real server will satisfy once consumed end-to-end. Reviewers
// see real shapes; production never gets touched by preview traffic.
//
// The fixture is sourced from `grid/api/examples/get-puzzle-200.json`
// via the `virtual:grid-api-examples/*` Vite plugin (see
// `vite.config.ts`). That keeps the spec as the single source of
// truth — there is no committed JSON copy in `frontend/` to drift
// from the spec, and ADR-0003 §9's "replay the spec's examples"
// pattern is honored verbatim.
//
// This module is reached only from `main.tsx` and only when
// `import.meta.env.VITE_USE_MOCK_API === 'true'` (preview builds). Vite
// tree-shakes the dynamic-import branch — and therefore `msw` and
// these handlers — out of production bundles. Verify with:
//
//   pnpm build && grep -r setupWorker dist/  # → no matches.

import { http, HttpResponse } from 'msw';

import type { components } from '@/infrastructure/api/grid/types';
// `virtual:grid-api-examples/*` is resolved by the
// `gridApiExamplesAsVirtualModule` plugin in `vite.config.ts`.
import getPuzzleExample from 'virtual:grid-api-examples/get-puzzle-200';

type Puzzle = components['schemas']['Puzzle'];

// The fixture is the spec example as JSON. Cast through `unknown`
// because the virtual module loader returns `any` JSON; the cast
// is checked by `pnpm api:check` (regen-and-diff against the spec).
const puzzleFixture = getPuzzleExample as unknown as Puzzle;

/**
 * Handlers for every Grid API operation declared in
 * `grid/api/openapi.yaml`. The path patterns use a `*` host prefix so
 * they match both `${VITE_GRID_API_URL}/v1/...` (preview's effective
 * URL) and any same-origin proxy a future ADR introduces.
 */
export const handlers = [
  http.get('*/v1/puzzles/:puzzleId', ({ params }) => {
    // Echo the requested id back so the round-trip looks realistic
    // (the spec example's hard-coded UUID would otherwise mismatch
    // whatever id the application layer asked for).
    const puzzleId = String(params.puzzleId);
    return HttpResponse.json({ ...puzzleFixture, id: puzzleId });
  }),
];
