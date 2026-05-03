// Pure helper: given a position and a direction, return every letter
// cell that belongs to the same "word" — i.e. the contiguous run of
// letter cells reachable along the direction's axis without crossing a
// block, a definition cell, or the grid edge.
//
// Used by both:
//   1. `useGridNavigation` — switched off the clue-driven walk so the
//      local "current word" tint and the overlay's remote-presence word
//      tint stay in lockstep (a remote presence carries `(row, column,
//      direction)`, NOT a clue id, so the clue-walk path is unavailable
//      to it). Keeping a single helper means the same range answers both.
//   2. `PresenceOverlay` — derives one word range per remote presence
//      and renders a translucent tint per cell.
//
// Pure function: takes `puzzle`, `position`, `direction` → returns the
// cell list. No React, no DOM, no side effects. Trivially unit-testable.

import type { Position, Puzzle } from '@/domain';

export type WordDirection = 'across' | 'down';

// Walk both ways from `position` along the chosen axis, gathering letter
// cells until the run hits a block, a definition cell, or the grid edge.
// Returns the cells in row-major order (sorted by row then col) so
// downstream renderers iterate predictably.
//
// Returns `[]` when:
//   - `position` is out of bounds, OR
//   - the cell at `position` is not a letter cell (a block / definition
//     cell has no surrounding "word" in the player-input sense).
export function wordRange(
  puzzle: Puzzle,
  position: Position,
  direction: WordDirection,
): readonly Position[] {
  if (position.row < 0 || position.row >= puzzle.height) return [];
  if (position.col < 0 || position.col >= puzzle.width) return [];

  // Build a quick position → cell index. Cells absent from `puzzle.cells`
  // are treated as block-like (the grid renders them as empty squares,
  // and they break a word run in `useGridNavigation` already).
  const byPos = new Map<string, 'letter' | 'definition' | 'block'>();
  for (const cell of puzzle.cells) {
    byPos.set(`${cell.position.row},${cell.position.col}`, cell.kind);
  }
  const kindAt = (row: number, col: number): 'letter' | 'definition' | 'block' | 'absent' => {
    if (row < 0 || row >= puzzle.height) return 'absent';
    if (col < 0 || col >= puzzle.width) return 'absent';
    return byPos.get(`${row},${col}`) ?? 'absent';
  };

  if (kindAt(position.row, position.col) !== 'letter') return [];

  const dr = direction === 'down' ? 1 : 0;
  const dc = direction === 'across' ? 1 : 0;

  // Walk forward until a non-letter (block/definition/absent/out-of-bounds).
  const cells: Position[] = [{ row: position.row, col: position.col }];
  let row = position.row + dr;
  let col = position.col + dc;
  while (kindAt(row, col) === 'letter') {
    cells.push({ row, col });
    row += dr; col += dc;
  }
  // Walk backward, prepending so the result stays sorted.
  row = position.row - dr;
  col = position.col - dc;
  while (kindAt(row, col) === 'letter') {
    cells.unshift({ row, col });
    row -= dr; col -= dc;
  }
  return cells;
}
