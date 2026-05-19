import { describe, expect, it } from 'vitest';
import type { Cell, Puzzle } from '@/domain';
import { wordRange } from '@/ui/components/grid/wordRange';

// Pure-function tests for the word-range walk that both
// `useGridNavigation` (local current-word tint) and `PresenceOverlay`
// (remote per-cursor tint) consume. Same range answers both code paths
// for any (position, direction) pair — these tests pin the contract.
//
// Layout (5×4): D = definition, X = letter, B = block.
//   D→  X    D↓   X    X
//   D→  X    X    X    X
//   X   X    X    X    X
//   X   B    X    X    X
//
// across-2 starts at (1,1) and runs to (1,4).
// down-1  starts at (1,2) and runs to (3,2).
// (3,1) is a block.

const L = (row: number, col: number): Cell =>
  ({ kind: 'letter', position: { row, col }, entry: '' });

const TEST_PUZZLE: Puzzle = {
  id: 'test', title: 'test', language: 'fr', width: 5, height: 4, hintsAllowed: 3, hintsRemaining: 3,
  cells: [
    { kind: 'definition', position: { row: 0, col: 0 }, clues: [{ text: 'across-1', arrow: 'right' }] },
    L(0, 1),
    { kind: 'definition', position: { row: 0, col: 2 }, clues: [{ text: 'down-1', arrow: 'down' }] },
    L(0, 3), L(0, 4),
    { kind: 'definition', position: { row: 1, col: 0 }, clues: [{ text: 'across-2', arrow: 'right' }] },
    L(1, 1), L(1, 2), L(1, 3), L(1, 4),
    L(2, 0), L(2, 1), L(2, 2), L(2, 3), L(2, 4),
    L(3, 0),
    { kind: 'block', position: { row: 3, col: 1 } },
    L(3, 2), L(3, 3), L(3, 4),
  ],
};

describe('wordRange — across', () => {
  it('returns the full across run from any starting cell within the word', () => {
    // Starting in the middle of across-2 walks both ways.
    const range = wordRange(TEST_PUZZLE, { row: 1, col: 3 }, 'across');
    expect(range.map((p) => `${p.row},${p.col}`)).toEqual(['1,1', '1,2', '1,3', '1,4']);
  });

  it('walks until the row right edge', () => {
    const range = wordRange(TEST_PUZZLE, { row: 2, col: 0 }, 'across');
    // Row 2 is all letters: (2,0) → (2,4).
    expect(range).toHaveLength(5);
    expect(range[0]).toEqual({ row: 2, col: 0 });
    expect(range[4]).toEqual({ row: 2, col: 4 });
  });

  it('stops at a definition cell', () => {
    // Across at (0,1) hits the definition at (0,2) immediately to the
    // right, so the range is just (0,1) — no further forward walk.
    const range = wordRange(TEST_PUZZLE, { row: 0, col: 1 }, 'across');
    expect(range).toEqual([{ row: 0, col: 1 }]);
  });
});

describe('wordRange — down', () => {
  it('returns the full down run from any starting cell within the word', () => {
    // (2,2) is in the middle of down-1, which spans (1,2)..(3,2).
    const range = wordRange(TEST_PUZZLE, { row: 2, col: 2 }, 'down');
    expect(range.map((p) => `${p.row},${p.col}`)).toEqual(['1,2', '2,2', '3,2']);
  });

  it('stops above a block when walking down', () => {
    // (1,1) walks down → (2,1). (3,1) is a block, so the down run is
    // (1,1) and (2,1); the upward walk hits the def at (0,0) — wait,
    // (0,1) is a letter. So the up walk includes (0,1).
    const range = wordRange(TEST_PUZZLE, { row: 1, col: 1 }, 'down');
    expect(range.map((p) => `${p.row},${p.col}`)).toEqual(['0,1', '1,1', '2,1']);
  });

  it('stops at the grid bottom edge', () => {
    const range = wordRange(TEST_PUZZLE, { row: 3, col: 4 }, 'down');
    expect(range[range.length - 1]).toEqual({ row: 3, col: 4 });
  });
});

describe('wordRange — edge cases', () => {
  it('returns [] for an out-of-bounds position', () => {
    expect(wordRange(TEST_PUZZLE, { row: -1, col: 0 }, 'across')).toEqual([]);
    expect(wordRange(TEST_PUZZLE, { row: 0, col: 99 }, 'down')).toEqual([]);
  });

  it('returns [] when the position is on a definition cell', () => {
    expect(wordRange(TEST_PUZZLE, { row: 0, col: 0 }, 'across')).toEqual([]);
  });

  it('returns [] when the position is on a block cell', () => {
    expect(wordRange(TEST_PUZZLE, { row: 3, col: 1 }, 'across')).toEqual([]);
  });
});
