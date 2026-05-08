import { describe, expect, it, vi } from 'vitest';
import { createHttpPuzzleSolver } from '@/infrastructure';
import { HintRequestError } from '@/application';

const json = (body: unknown, status = 200, type = 'application/json') =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': type },
  });

const PUZZLE_ID = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b';

describe('HttpPuzzleSolver — validate', () => {
  it('POSTs filledCells against /v1/puzzles/{id}/validate and unwraps the result', async () => {
    const fetchSpy = vi.fn().mockResolvedValue(
      json({ solved: false, incorrectCells: [{ row: 1, column: 2 }] }),
    );
    const solver = createHttpPuzzleSolver({
      sessionId: '019186c5-d702-7b3a-a3d4-b40dc2d6d871',
      baseUrl: 'https://api.example.test',
      fetch: fetchSpy,
    });

    const result = await solver.validate(PUZZLE_ID, [
      { row: 0, column: 1, letter: 'P' },
      { row: 0, column: 3, letter: 'G' },
    ]);

    const call = fetchSpy.mock.calls[0][0];
    const url = call instanceof Request ? call.url : String(call);
    const init = call instanceof Request ? call : fetchSpy.mock.calls[0][1];
    expect(url).toBe(`https://api.example.test/v1/puzzles/${PUZZLE_ID}/validate`);
    expect((init as Request).method ?? init.method).toBe('POST');
    const body = await (init as Request).clone().text();
    expect(JSON.parse(body)).toEqual({
      filledCells: [
        { row: 0, column: 1, letter: 'P' },
        { row: 0, column: 3, letter: 'G' },
      ],
    });
    expect(result).toEqual({
      solved: false,
      incorrectCells: [{ row: 1, column: 2 }],
    });
  });

  it('rejects with the RFC 7807 detail on a 400 problem body', async () => {
    const solver = createHttpPuzzleSolver({
      sessionId: '019186c5-d702-7b3a-a3d4-b40dc2d6d871',
      baseUrl: 'https://api.example.test',
      fetch: vi.fn().mockResolvedValue(
        json(
          {
            type: 'https://bliss.example/errors/invalid-validate-request',
            title: 'Invalid request',
            status: 400,
            detail: 'duplicate (row, column)',
          },
          400,
          'application/problem+json',
        ),
      ),
    });
    await expect(
      solver.validate(PUZZLE_ID, [{ row: 0, column: 0, letter: 'A' }]),
    ).rejects.toThrow(/duplicate/);
  });
});

describe('HttpPuzzleSolver — requestHint', () => {
  it('POSTs the word to /v1/puzzles/{id}/hints and unwraps the result', async () => {
    const fetchSpy = vi.fn().mockResolvedValue(
      json({ word: 'forêt', exists: true, hintsRemaining: 2 }),
    );
    const solver = createHttpPuzzleSolver({
      sessionId: '019186c5-d702-7b3a-a3d4-b40dc2d6d871',
      baseUrl: 'https://api.example.test',
      fetch: fetchSpy,
    });

    const result = await solver.requestHint(PUZZLE_ID, 'forêt');

    const call = fetchSpy.mock.calls[0][0];
    const url = call instanceof Request ? call.url : String(call);
    expect(url).toBe(`https://api.example.test/v1/puzzles/${PUZZLE_ID}/hints`);
    expect(result).toEqual({ word: 'forêt', exists: true, hintsRemaining: 2 });
  });

  it('throws HintRequestError(budget-exhausted) on 429', async () => {
    const solver = createHttpPuzzleSolver({
      sessionId: '019186c5-d702-7b3a-a3d4-b40dc2d6d871',
      baseUrl: 'https://api.example.test',
      fetch: vi.fn().mockResolvedValue(
        json(
          {
            type: 'https://bliss.example/errors/hint-budget-exhausted',
            title: 'Indices épuisés',
            status: 429,
            detail: 'hintsRemaining: 0',
          },
          429,
          'application/problem+json',
        ),
      ),
    });
    try {
      await solver.requestHint(PUZZLE_ID, 'forêt');
      expect.fail('expected HintRequestError to throw');
    } catch (err) {
      expect(err).toBeInstanceOf(HintRequestError);
      const e = err as HintRequestError;
      expect(e.kind).toBe('budget-exhausted');
      expect(e.hintsRemaining).toBe(0);
    }
  });

  it('throws HintRequestError(invalid-word) on 400', async () => {
    const solver = createHttpPuzzleSolver({
      sessionId: '019186c5-d702-7b3a-a3d4-b40dc2d6d871',
      baseUrl: 'https://api.example.test',
      fetch: vi.fn().mockResolvedValue(
        json(
          {
            type: 'https://bliss.example/errors/invalid-word',
            title: 'Mot invalide',
            status: 400,
            detail: 'word must be 2–50 letters',
          },
          400,
          'application/problem+json',
        ),
      ),
    });
    try {
      await solver.requestHint(PUZZLE_ID, 'a');
      expect.fail('expected HintRequestError to throw');
    } catch (err) {
      expect(err).toBeInstanceOf(HintRequestError);
      expect((err as HintRequestError).kind).toBe('invalid-word');
    }
  });

  it('throws HintRequestError(transient) on 5xx', async () => {
    const solver = createHttpPuzzleSolver({
      sessionId: '019186c5-d702-7b3a-a3d4-b40dc2d6d871',
      baseUrl: 'https://api.example.test',
      fetch: vi.fn().mockResolvedValue(
        json(
          { type: 'https://x/err', title: 'Upstream', status: 503 },
          503,
          'application/problem+json',
        ),
      ),
    });
    try {
      await solver.requestHint(PUZZLE_ID, 'forêt');
      expect.fail('expected HintRequestError to throw');
    } catch (err) {
      expect(err).toBeInstanceOf(HintRequestError);
      expect((err as HintRequestError).kind).toBe('transient');
    }
  });
});
