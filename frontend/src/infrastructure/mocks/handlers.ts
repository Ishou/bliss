// MSW request handlers for Cloudflare Pages preview deploys.
//
// Per ADR-0007 §5 and ADR-0009 §7, per-PR previews stay frontend-only:
// the real Grid and Game APIs run only on `main`, so previews would
// otherwise either point at production (data-leak risk + noise on real
// metrics) or 404. These handlers intercept every Grid REST call, every
// Game REST call, and every Game WebSocket connection in the browser
// and replay the spec's contract — exactly the surface the real servers
// will satisfy once consumed end-to-end. Reviewers see real shapes;
// production never gets touched by preview traffic.
//
// The grid fixture is sourced from `./fixtures/puzzle.json`, a 10×10
// French mots-fléchés grid with realistic clue text — this is what
// the dev/preview reviewer sees, and what the e2e harness loads to
// stress-test the FitText layout. The same JSON powers both, so any
// "looks fine in preview, breaks in e2e" drift is impossible.
// (The OpenAPI spec example at `grid/api/examples/get-puzzle-200.json`
// is still the contract source for `tests/http-puzzle-repository.test.ts`
// via the `virtual:grid-api-examples/*` Vite plugin; preview's display
// fixture is intentionally separate so we can swap clue corpora without
// touching the spec.) The game/lobby surface is hand-built in
// `handlers/game.ts` because the game/api spec doesn't ship `examples/`
// payloads yet — the generated TS types are the contract, the WS frames
// mirror `game/api/asyncapi.yaml`.
//
// This module is reached only from `main.tsx` and only when
// `import.meta.env.VITE_USE_MOCK_API === 'true'` (preview builds). Vite
// tree-shakes the dynamic-import branch — and therefore `msw` and
// these handlers — out of production bundles. Verify with:
//
//   pnpm build && grep -r setupWorker dist/  # → no matches.

import { http, HttpResponse } from 'msw';

import type { components } from '@/infrastructure/api/grid/types';
import puzzleFixtureJson from './fixtures/puzzle.json';

import { gameHandlers, gameWsHandler } from './handlers/game';

type Puzzle = components['schemas']['Puzzle'];

// Cast through `unknown` because Vite's JSON import returns the
// inferred literal type; the cast is structurally validated at runtime
// by MSW returning it through the Grid API client (which has spec-
// generated types).
const puzzleFixture = puzzleFixtureJson as unknown as Puzzle;

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
// `/v1/puzzles/...`, game REST is `/v1/lobbies/...`, game WS is its own
// dimension. The WS link handler is appended last by convention.
export const handlers = [...gridHandlers, ...gameHandlers, gameWsHandler];
