import { describe, expect, it, vi } from 'vitest';
import { createHttpPuzzleRepository } from '@/infrastructure';
import type { components } from '@/infrastructure/api/grid/types';

type ApiPuzzle = components['schemas']['Puzzle'];
const apiFixture: ApiPuzzle = {
  id: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  title: 't', language: 'fr', width: 1, height: 1,
  createdAt: '2026-04-24T15:30:00Z', clues: [],
  cells: [{ kind: 'letter', position: { row: 0, column: 0 }, letter: 'A' }],
};
const json = (body: unknown, status = 200, type = 'application/json') =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': type } });

describe('HttpPuzzleRepository', () => {
  it('GETs /v1/puzzles/{id} against baseUrl and maps to a domain Puzzle', async () => {
    const fetchSpy = vi.fn().mockResolvedValue(json(apiFixture));
    const repo = createHttpPuzzleRepository({
      baseUrl: 'https://api.example.test', fetch: fetchSpy,
    });

    const puzzle = await repo.fetchById(apiFixture.id);

    const call = fetchSpy.mock.calls[0][0];
    const url = call instanceof Request ? call.url : String(call);
    expect(url).toBe(`https://api.example.test/v1/puzzles/${apiFixture.id}`);
    expect(puzzle.cells[0]).toEqual({
      kind: 'letter', position: { row: 0, col: 0 }, answer: 'A', entry: '',
    });
  });

  it('rejects with the RFC 7807 detail when the API returns a problem body', async () => {
    const repo = createHttpPuzzleRepository({
      baseUrl: 'https://api.example.test',
      fetch: vi.fn().mockResolvedValue(
        json(
          { type: 'https://x/err', title: 'Generation failed', status: 503, detail: 'Out of attempts' },
          503, 'application/problem+json',
        ),
      ),
    });
    await expect(repo.fetchById(apiFixture.id)).rejects.toThrow(/Out of attempts/);
  });
});
