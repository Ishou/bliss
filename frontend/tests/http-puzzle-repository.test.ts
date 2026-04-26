import { describe, expect, it, vi } from 'vitest';
import { createHttpPuzzleRepository } from '@/infrastructure';
import getFixture from '../../grid/api/examples/get-puzzle-200.json';

const json = (body: unknown, status = 200, type = 'application/json') =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': type } });

describe('HttpPuzzleRepository', () => {
  it('GETs /v1/puzzles/{id} against baseUrl and maps to a domain Puzzle', async () => {
    const fetchSpy = vi.fn().mockResolvedValue(json(getFixture));
    const repo = createHttpPuzzleRepository({
      baseUrl: 'https://api.example.test', fetch: fetchSpy,
    });

    const puzzle = await repo.fetchById(getFixture.id);

    const call = fetchSpy.mock.calls[0][0];
    const url = call instanceof Request ? call.url : String(call);
    expect(url).toBe(`https://api.example.test/v1/puzzles/${getFixture.id}`);
    // block cell
    expect(puzzle.cells[0]).toEqual({ kind: 'block', position: { row: 0, col: 0 } });
    // definition cell — clues are not dropped on the floor
    expect(puzzle.cells[1]).toEqual({
      kind: 'definition', position: { row: 0, col: 1 },
      clues: [{ text: 'Capitale de la France', arrow: 'right' }],
    });
    // pre-filled letter cell
    expect(puzzle.cells[10]).toEqual({
      kind: 'letter', position: { row: 2, col: 0 }, answer: 'B', entry: '',
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
    await expect(repo.fetchById(getFixture.id)).rejects.toThrow(/Out of attempts/);
  });
});
