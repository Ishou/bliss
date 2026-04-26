import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { setupServer } from 'msw/node';
import { handlers } from '@/infrastructure/mocks/handlers';
import type { components } from '@/infrastructure/api/grid/types';
import getFixture from '../../grid/api/examples/get-puzzle-200.json';

// Contract test for the MSW preview handlers (ADR-0003 §9, ADR-0007 §5).
// Asserts that the getPuzzle handler (a) returns HTTP 200, (b) echoes
// the requested puzzleId, and (c) preserves the spec fixture's shape.

type Puzzle = components['schemas']['Puzzle'];
const specFixture = getFixture as unknown as Puzzle;

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

  it('GET /v1/puzzles/:puzzleId body otherwise matches the spec fixture shape', async () => {
    const puzzleId = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
    const response = await fetch(`http://localhost/v1/puzzles/${puzzleId}`);
    const body = (await response.json()) as Puzzle;

    // id is overridden with the requested value; all other fields come from the fixture
    expect(body.id).toBe(puzzleId);
    expect(body.title).toBe(specFixture.title);
    expect(body.width).toBe(specFixture.width);
    expect(body.height).toBe(specFixture.height);
    expect(body.clues).toEqual(specFixture.clues);
  });
});
