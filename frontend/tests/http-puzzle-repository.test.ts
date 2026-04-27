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
    expect(puzzle.width).toBe(5);
    expect(puzzle.height).toBe(5);
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
    // letter cell at (2,0) — pre-filled; verifies LetterCell.letter → domain answer mapping
    expect(puzzle.cells[10]).toEqual({
      kind: 'letter', position: { row: 2, col: 0 }, answer: 'P', entry: '',
    });
    // the wire's top-level `clues` array is non-empty and every clue
    // text surfaces on a `DefinitionCell` in the domain — proves the
    // stacked-clue mapping is exercised end-to-end.
    expect(apiFixture.clues.length).toBeGreaterThan(0);
    const definitionTexts = puzzle.cells
      .filter((c): c is Extract<typeof c, { kind: 'definition' }> => c.kind === 'definition')
      .flatMap((c) => c.clues.map((cc) => cc.text));
    for (const wire of apiFixture.clues) {
      expect(definitionTexts).toContain(wire.text);
    }
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
