import { describe, expect, it, vi } from 'vitest';
import { createHttpPuzzleRepository } from '@/infrastructure';
import type { components } from '@/infrastructure/api/grid/types';
import getFixture from '../../grid/api/examples/get-puzzle-200.json';

// Anchor the contract test to the canonical OpenAPI example
// (`grid/api/examples/get-puzzle-200.json`) per ADR-0003 §9. Typing the
// imported JSON as `ApiPuzzle` preserves end-to-end type safety: if the
// generated wire type drifts from the example, this file fails to
// compile.
type ApiPuzzle = components['schemas']['Puzzle'];
const apiFixture = getFixture as unknown as ApiPuzzle;

// Inline fixture exercising the stacked-cell (two DefinitionCells at the
// same position) path in the mapper. Kept here so the canonical fixture in
// `grid/api/examples/` remains owned by the `grid` bounded context.
const stackedFixture: ApiPuzzle = {
  id: '01900000-0000-7000-0000-000000000001',
  title: 'Stacked test',
  language: 'fr',
  width: 2,
  height: 2,
  createdAt: '2026-01-01T00:00:00Z',
  clues: [
    {
      id: '01900000-0000-7000-0000-0000000000c1',
      direction: 'across',
      start: { row: 0, column: 1 },
      length: 1,
      text: 'Capitale de la France',
    },
    {
      id: '01900000-0000-7000-0000-0000000000c2',
      direction: 'down',
      start: { row: 1, column: 0 },
      length: 1,
      text: 'Couleur du ciel',
    },
  ],
  cells: [
    { kind: 'definition', position: { row: 0, column: 0 }, clueId: '01900000-0000-7000-0000-0000000000c1', text: 'Capitale de la France', arrow: 'right' },
    { kind: 'definition', position: { row: 0, column: 0 }, clueId: '01900000-0000-7000-0000-0000000000c2', text: 'Couleur du ciel', arrow: 'down' },
    { kind: 'letter', position: { row: 0, column: 1 } },
    { kind: 'letter', position: { row: 1, column: 0 } },
    { kind: 'letter', position: { row: 1, column: 1 } },
  ],
} as unknown as ApiPuzzle;

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
    // identity & dimensions round-trip from the spec fixture
    expect(puzzle.id).toBe(apiFixture.id);
    expect(puzzle.width).toBe(apiFixture.width);
    expect(puzzle.height).toBe(apiFixture.height);
    // every letter cell has no answer (LetterCell.letter removed from wire — PR #218)
    const letterCells = puzzle.cells.filter((c) => c.kind === 'letter');
    expect(letterCells.every((c) => !('answer' in c))).toBe(true);
    // the wire's top-level `clues` array is non-empty and every clue
    // text surfaces on a `DefinitionCell` in the domain — proves the
    // mapping is exercised end-to-end.
    expect(apiFixture.clues.length).toBeGreaterThan(0);
    const definitionTexts = puzzle.cells
      .filter((c): c is Extract<typeof c, { kind: 'definition' }> => c.kind === 'definition')
      .flatMap((c) => c.clues.map((cc) => cc.text));
    for (const wire of apiFixture.clues) {
      expect(definitionTexts).toContain(wire.text);
    }
  });

  it('maps stacked definition cells (two clues at same position) to a single domain cell', async () => {
    const fetchSpy = vi.fn().mockResolvedValue(json(stackedFixture));
    const repo = createHttpPuzzleRepository({
      baseUrl: 'https://api.example.test', fetch: fetchSpy,
    });

    const puzzle = await repo.fetchById(stackedFixture.id);

    // stacked definition cell at (0,0) — both clues must be preserved
    expect(puzzle.cells[0]).toEqual({
      kind: 'definition', position: { row: 0, col: 0 },
      clues: [
        { text: 'Capitale de la France', arrow: 'right' },
        { text: 'Couleur du ciel', arrow: 'down' },
      ],
    });
    // first letter cell following the stacked def
    expect(puzzle.cells[1]).toEqual({
      kind: 'letter', position: { row: 0, col: 1 }, entry: '',
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

  it('GETs /v1/puzzles/daily and maps gridNumber + difficulty to the domain Puzzle', async () => {
    const fetchSpy = vi.fn().mockResolvedValue(json(apiFixture));
    const repo = createHttpPuzzleRepository({
      baseUrl: 'https://api.example.test', fetch: fetchSpy,
    });

    const puzzle = await repo.fetchDaily();

    const call = fetchSpy.mock.calls[0][0];
    const url = call instanceof Request ? call.url : String(call);
    expect(url).toBe('https://api.example.test/v1/puzzles/daily');
    expect(puzzle.id).toBe(apiFixture.id);
    expect(puzzle.gridNumber).toBe(apiFixture.gridNumber);
    expect(puzzle.difficulty).toBe(apiFixture.difficulty);
  });

  it('GETs /v1/puzzles/daily?date=... when date is provided', async () => {
    const fetchSpy = vi.fn().mockResolvedValue(json(apiFixture));
    const repo = createHttpPuzzleRepository({
      baseUrl: 'https://api.example.test', fetch: fetchSpy,
    });

    await repo.fetchDaily('2026-05-09');

    const call = fetchSpy.mock.calls[0][0];
    const url = call instanceof Request ? call.url : String(call);
    expect(url).toContain('/v1/puzzles/daily');
    expect(url).toContain('date=2026-05-09');
  });

  it('rejects with the RFC 7807 detail when fetchDaily returns a problem body', async () => {
    const repo = createHttpPuzzleRepository({
      baseUrl: 'https://api.example.test',
      fetch: vi.fn().mockResolvedValue(
        json(
          { type: 'https://x/err', title: 'Invalid date', status: 400, detail: 'date must be ISO-8601' },
          400, 'application/problem+json',
        ),
      ),
    });
    await expect(repo.fetchDaily('bad-date')).rejects.toThrow(/date must be ISO-8601/);
  });
});
