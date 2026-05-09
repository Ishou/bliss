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
// `import.meta.env.VITE_MOCK_GRID_API === 'true'` (preview builds). Vite
// tree-shakes the dynamic-import branch — and therefore `msw` and
// these handlers — out of production bundles. Verify with:
//
//   pnpm build && grep -r setupWorker dist/  # → no matches.

import { http, HttpResponse } from 'msw';

import type { components } from '@/infrastructure/api/grid/types';
import puzzleFixtureJson from './fixtures/puzzle.json';

import { gameHandlers, gameWsHandler } from './handlers/game';

type Puzzle = components['schemas']['Puzzle'];
type Position = components['schemas']['Position'];
type ValidatePuzzleRequest = components['schemas']['ValidatePuzzleRequest'];
type RevealCellHintRequest = components['schemas']['RevealCellHintRequest'];

// Cast through `unknown` because Vite's JSON import returns the
// inferred literal type; the cast is structurally validated at runtime
// by MSW returning it through the Grid API client (which has spec-
// generated types).
const puzzleFixture = puzzleFixtureJson as unknown as Puzzle;

// Preview parity for the `/validate` endpoint: extract the canonical
// letter from each fixture letter cell. The wire `LetterCell` no longer
// carries `letter` (PR #218), but the preview JSON does — they are the
// expected answers the player is supposed to type. Used solely by the
// mock handler below; nothing on the contract surface depends on this.
type FixtureLetterCell = { kind: 'letter'; position: Position; letter?: string };
const fixtureLetterByPosition = new Map<string, string>();
for (const cell of puzzleFixtureJson.cells as readonly FixtureLetterCell[]) {
  if (cell.kind !== 'letter' || !cell.letter) continue;
  fixtureLetterByPosition.set(
    `${cell.position.row},${cell.position.column}`,
    cell.letter.toUpperCase().normalize('NFD').replace(/[̀-ͯ]/g, ''),
  );
}
const fixtureLetterCellCount = fixtureLetterByPosition.size;

// Per-puzzle hint counter. Resets on module reload (preview only). The
// real server scopes this per (puzzle, player); for MSW we only need
// the budget-exhaustion transition.
const hintsRemainingByPuzzle = new Map<string, number>();

const HINT_PROBLEM_BUDGET = {
  type: 'https://bliss.example/errors/hint-budget-exhausted',
  title: 'Indices épuisés',
  status: 429,
  detail: 'hintsRemaining: 0',
};
const HINT_PROBLEM_INVALID_COORD = {
  type: 'https://bliss.example/errors/invalid-coord',
  title: 'Coordonnées invalides',
  status: 400,
  detail: 'coordinate is out of bounds or does not point at a letter cell',
};

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

  // POST /v1/puzzles/{id}/hints — preview parity. Looks up the canonical
  // letter at (row, column) in the fixture, decrements a per-puzzle
  // counter seeded from `puzzleFixture.hintsAllowed`; once it hits zero
  // the next call returns a 429 problem+json, mirroring the real
  // server's `hint-budget-exhausted` shape. Coordinates that don't map
  // to a fixture letter cell return a 400 `invalid-coord` and the
  // budget is left untouched.
  http.post('*/v1/puzzles/:puzzleId/hints', async ({ params, request }) => {
    const puzzleId = String(params.puzzleId);
    const body = (await request.json()) as RevealCellHintRequest;
    const expected = fixtureLetterByPosition.get(`${body.row},${body.column}`);
    if (!expected) {
      return HttpResponse.json(HINT_PROBLEM_INVALID_COORD, {
        status: 400,
        headers: { 'content-type': 'application/problem+json' },
      });
    }
    const remaining = hintsRemainingByPuzzle.get(puzzleId) ??
      (puzzleFixture.hintsAllowed ?? 3);
    if (remaining <= 0) {
      return HttpResponse.json(HINT_PROBLEM_BUDGET, {
        status: 429,
        headers: { 'content-type': 'application/problem+json' },
      });
    }
    const next = remaining - 1;
    hintsRemainingByPuzzle.set(puzzleId, next);
    return HttpResponse.json({
      row: body.row,
      column: body.column,
      letter: expected,
      hintsRemaining: next,
    });
  }),

  // POST /v1/puzzles/{id}/validate — preview parity. Compares the
  // submitted letters against the fixture's canonical letters; reports
  // every wrong-letter AND every unfilled letter cell as `incorrectCells`.
  // The real server runs the same comparison server-side; for preview we
  // borrow the JSON's `letter` field to avoid shipping a separate answer
  // sheet.
  http.post('*/v1/puzzles/:puzzleId/validate', async ({ request }) => {
    const body = (await request.json()) as ValidatePuzzleRequest;
    const submitted = new Map<string, string>();
    for (const cell of body.filledCells ?? []) {
      submitted.set(`${cell.row},${cell.column}`, cell.letter);
    }
    const incorrect: Position[] = [];
    for (const [key, expected] of fixtureLetterByPosition) {
      const got = submitted.get(key);
      if (got !== expected) {
        const [row, column] = key.split(',').map(Number);
        incorrect.push({ row, column });
      }
    }
    return HttpResponse.json({
      solved:
        incorrect.length === 0 && submitted.size === fixtureLetterCellCount,
      incorrectCells: incorrect,
    });
  }),
];


// Order matters only for overlap, which we don't have: grid REST is
// `/v1/puzzles/...`, game REST is `/v1/lobbies/...`, game WS is its own
// dimension. The WS link handler is appended last by convention.
//
// Handler groups are exported separately so the composition root can
// install the grid set, the game set, or both depending on env. The
// dev posture (real grid backend on localhost, mocked game-api) wants
// the game set only; preview wants both; production installs neither.
export const gridApiHandlers = gridHandlers;
export const gameApiHandlers = [...gameHandlers, gameWsHandler];
export const handlers = [...gridApiHandlers, ...gameApiHandlers];
