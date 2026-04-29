import type { Position } from './Position';

// Direction in which a definition's answer flows. `right` means the answer
// occupies the cells immediately to the right of the definition cell;
// `down` means it occupies the cells immediately below. v1 deliberately
// excludes diagonal-split cells; the stacked variant below is the only
// way to fit two clues into a single cell.
export type ArrowDirection = 'right' | 'down' | 'down-right' | 'right-down';

// A cell where the player types one letter. `answer` is the canonical
// solution kept for future "check / reveal" features; `entry` is the
// player's current input (empty string when blank). Both are uppercase
// French letters in v1; locale-specific normalization is the application
// layer's job.
export interface LetterCell {
  readonly kind: 'letter';
  readonly position: Position;
  readonly answer?: string;
  readonly entry: string;
}

// A single clue inside a definition cell: the prose text the player reads
// and the arrow that anchors its answer path on the grid.
export interface DefinitionClue {
  readonly text: string;
  readonly arrow: ArrowDirection;
}

// A clue cell. Carries one or two clues per ADR-0005 §3a. Dual cells most
// commonly mix axes (one horizontal, one vertical) — that's the corner cell —
// but the boundary skeleton also produces same-axis duals: top-row inner
// clues are RIGHT_DOWN + DOWN (both vertical, one in the next column, one in
// this column), and left-col inner clues are DOWN_RIGHT + RIGHT (both
// horizontal, one in the next row, one in this row). The renderer must
// handle any pair.
export type HorizontalArrow = 'right' | 'down-right';
export type VerticalArrow = 'down' | 'right-down';

export interface DefinitionCell {
  readonly kind: 'definition';
  readonly position: Position;
  readonly clues:
    | readonly [DefinitionClue]
    | readonly [DefinitionClue, DefinitionClue];
}

// An inert solid square — neither a clue nor an input.
export interface BlockCell {
  readonly kind: 'block';
  readonly position: Position;
}

export type Cell = LetterCell | DefinitionCell | BlockCell;
