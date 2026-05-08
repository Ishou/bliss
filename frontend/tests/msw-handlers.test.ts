import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { setupServer } from 'msw/node';
import { handlers } from '@/infrastructure/mocks/handlers';
import type { components } from '@/infrastructure/api/grid/types';
import puzzleFixture from '@/infrastructure/mocks/fixtures/puzzle.json';

// Contract test for the MSW preview handlers (ADR-0003 §9, ADR-0007 §5).
// Asserts that the getPuzzle handler (a) returns HTTP 200, (b) echoes
// the requested puzzleId, and (c) returns the same fixture MSW is
// configured with. That fixture is intentionally NOT the OpenAPI spec
// example any more — preview/dev/e2e all serve a realistic 10×10 mots-
// fléchés grid so reviewers and the FitText layout harness see real
// content. The OpenAPI spec example is still the contract source for
// `tests/http-puzzle-repository.test.ts` (which exercises the wire
// surface against the spec).

type Puzzle = components['schemas']['Puzzle'];
const fixture = puzzleFixture as unknown as Puzzle;

const server = setupServer(...handlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('MSW preview handlers', () => {
  it('GET /v1/puzzles/:puzzleId returns 200 with the requested id echoed back', async () => {
    const puzzleId = 'ffffffff-0000-1111-2222-333333333333';
    const response = await fetch(`http://localhost/v1/puzzles/${puzzleId}`);

    expect(response.status).toBe(200);
    const body = (await response.json()) as Puzzle;
    expect(body.id).toBe(puzzleId);
  });

  it('GET /v1/puzzles/:puzzleId body otherwise matches the configured fixture', async () => {
    const puzzleId = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
    const response = await fetch(`http://localhost/v1/puzzles/${puzzleId}`);
    const body = (await response.json()) as Puzzle;

    // id is overridden with the requested value; all other fields come from the fixture
    expect(body.id).toBe(puzzleId);
    expect(body.title).toBe(fixture.title);
    expect(body.width).toBe(fixture.width);
    expect(body.height).toBe(fixture.height);
    expect(body.clues).toEqual(fixture.clues);
  });

  it('POST /v1/puzzles/:puzzleId/hints decrements the budget then 429s when exhausted', async () => {
    // Each test gets a fresh puzzleId so the per-puzzle counter starts
    // at the fixture's `hintsAllowed` (3) regardless of test order.
    const puzzleId = '11111111-2222-3333-4444-555555555555';
    const post = (word: string) =>
      fetch(`http://localhost/v1/puzzles/${puzzleId}/hints`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ word }),
      });

    const r1 = await post('forêt');
    expect(r1.status).toBe(200);
    expect(await r1.json()).toMatchObject({ exists: true, hintsRemaining: 2 });

    const r2 = await post('clé');
    expect(r2.status).toBe(200);
    expect(await r2.json()).toMatchObject({ hintsRemaining: 1 });

    const r3 = await post('arc');
    expect(r3.status).toBe(200);
    expect(await r3.json()).toMatchObject({ hintsRemaining: 0 });

    const r4 = await post('fin');
    expect(r4.status).toBe(429);
    expect(r4.headers.get('content-type')).toContain('application/problem+json');
    expect(await r4.json()).toMatchObject({
      type: 'https://bliss.example/errors/hint-budget-exhausted',
    });
  });

  it('POST /v1/puzzles/:puzzleId/validate reports incorrect cells against the fixture', async () => {
    const puzzleId = '99999999-aaaa-bbbb-cccc-dddddddddddd';
    const response = await fetch(
      `http://localhost/v1/puzzles/${puzzleId}/validate`,
      {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        // Intentionally empty: every fixture letter cell is "unfilled"
        // and therefore reported as incorrect; `solved` must be false.
        body: JSON.stringify({ filledCells: [] }),
      },
    );
    expect(response.status).toBe(200);
    const body = (await response.json()) as {
      solved: boolean;
      incorrectCells: ReadonlyArray<{ row: number; column: number }>;
    };
    expect(body.solved).toBe(false);
    expect(body.incorrectCells.length).toBeGreaterThan(0);
  });
});
