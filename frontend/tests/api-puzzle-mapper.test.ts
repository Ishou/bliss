import { describe, expect, it } from 'vitest';
import { apiPuzzleToDomain } from '@/infrastructure/api/grid/mapper';
import type { components } from '@/infrastructure/api/grid/types';

type ApiPuzzle = components['schemas']['Puzzle'];
const baseHeader: Omit<ApiPuzzle, 'width' | 'height' | 'cells'> = {
  id: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  title: 't', language: 'fr',
  createdAt: '2026-04-24T15:30:00Z',
  clues: [],
};

describe('apiPuzzleToDomain', () => {
  it('renames column→col, lifts letters/blocks/single-clue defs, omits `answer` when blank', () => {
    const api: ApiPuzzle = {
      ...baseHeader, width: 4, height: 1,
      cells: [
        { kind: 'block', position: { row: 0, column: 0 } },
        {
          kind: 'definition', position: { row: 0, column: 1 },
          clueId: 'c1', text: 'Capitale', arrow: 'right',
        },
        { kind: 'letter', position: { row: 0, column: 2 }, letter: 'A' },
        { kind: 'letter', position: { row: 0, column: 3 }, letter: null },
      ],
    };

    expect(apiPuzzleToDomain(api).cells).toEqual([
      { kind: 'block', position: { row: 0, col: 0 } },
      {
        kind: 'definition', position: { row: 0, col: 1 },
        clues: [{ text: 'Capitale', arrow: 'right' }],
      },
      { kind: 'letter', position: { row: 0, col: 2 }, answer: 'A', entry: '' },
      { kind: 'letter', position: { row: 0, col: 3 }, entry: '' },
    ]);
  });

  it('merges two definition cells at the same position into a [right, down] stack', () => {
    // Wire emits `down` first; ADR-0005 §3a still requires `right`
    // first in the domain stack regardless of wire order.
    const api: ApiPuzzle = {
      ...baseHeader, width: 2, height: 2,
      cells: [
        { kind: 'definition', position: { row: 0, column: 0 }, clueId: 'c2', text: 'Saison', arrow: 'down' },
        { kind: 'letter', position: { row: 0, column: 1 } },
        { kind: 'definition', position: { row: 0, column: 0 }, clueId: 'c1', text: 'Astre', arrow: 'right' },
        { kind: 'letter', position: { row: 1, column: 0 } },
      ],
    };

    const domain = apiPuzzleToDomain(api);
    expect(domain.cells).toHaveLength(3);
    const def = domain.cells[0];
    expect(def.kind).toBe('definition');
    if (def.kind !== 'definition') return;
    expect(def.clues).toEqual([
      { text: 'Astre', arrow: 'right' },
      { text: 'Saison', arrow: 'down' },
    ]);
  });

  it('keeps both clues for same-axis dual cells in API order (down-right + right)', () => {
    // left-col inner skeleton cells produce DOWN_RIGHT + RIGHT (both horizontal);
    // ADR-0005 §3a amendment 2: same-axis duals preserve API order, not horizontal-first.
    const api: ApiPuzzle = {
      ...baseHeader, width: 3, height: 1,
      cells: [
        { kind: 'definition', position: { row: 0, column: 0 }, clueId: 'c1', text: 'Animaux', arrow: 'down-right' },
        { kind: 'definition', position: { row: 0, column: 0 }, clueId: 'c2', text: 'Couleur', arrow: 'right' },
        { kind: 'letter', position: { row: 0, column: 1 } },
        { kind: 'letter', position: { row: 0, column: 2 } },
      ],
    };

    const domain = apiPuzzleToDomain(api);
    expect(domain.cells).toHaveLength(3);
    const def = domain.cells[0];
    expect(def.kind).toBe('definition');
    if (def.kind !== 'definition') return;
    expect(def.clues).toEqual([
      { text: 'Animaux', arrow: 'down-right' },
      { text: 'Couleur', arrow: 'right' },
    ]);
  });
});
