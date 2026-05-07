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
});
