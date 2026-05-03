// MSW request handlers for Cloudflare Pages preview deploys.
//
// Per ADR-0007 §5 and ADR-0009 §7, per-PR previews stay frontend-only:
// the real Grid and Game APIs run only on `main`, so previews would
// otherwise either point at production (data-leak risk + noise on real
// metrics) or 404. These handlers intercept every Grid REST call and
// every Game REST call in the browser and replay the spec's contract —
// exactly the surface the real servers will satisfy once consumed
// end-to-end. Reviewers see real shapes; production never gets touched
// by preview traffic.
//
// The grid fixture is sourced from `grid/api/examples/get-puzzle-200.json`
// via the `virtual:grid-api-examples/*` Vite plugin (see
// `vite.config.ts`). The game/lobby surface is hand-built in
// `handlers/game.ts` because the game/api spec doesn't ship `examples/`
// payloads yet — the generated TS types are the contract, the WS frames
// mirror `game/api/asyncapi.yaml`. ADR-0003 §9's "replay the spec's
// examples" pattern is honored where examples exist; where they don't,
// the wire shapes still match the spec byte-for-byte.
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

import { gameHandlers } from './handlers/game';

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
const gridHandlers = [
  http.get('*/v1/puzzles/:puzzleId', ({ params }) => {
    // Echo the requested id back so the round-trip looks realistic
    // (the spec example's hard-coded UUID would otherwise mismatch
    // whatever id the application layer asked for).
    const puzzleId = String(params.puzzleId);
    return HttpResponse.json({ ...puzzleFixture, id: puzzleId });
  }),
];

// Order matters only for overlap, which we don't have: grid REST is
// `/v1/puzzles/...`, game REST is `/v1/lobbies/...`.
export const handlers = [...gridHandlers, ...gameHandlers];
