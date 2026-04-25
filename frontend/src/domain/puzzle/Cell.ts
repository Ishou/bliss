import type { Position } from './Position';

// Direction in which a definition's answer flows. `right` means the answer
// occupies the cells immediately to the right of the definition cell;
// `down` means it occupies the cells immediately below. v1 deliberately
// excludes diagonal-split cells (one definition per cell).
export type ArrowDirection = 'right' | 'down';

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

// A clue cell. Renders the definition text and an arrow pointing at the
// first letter of the answer.
export interface DefinitionCell {
  readonly kind: 'definition';
  readonly position: Position;
  readonly text: string;
  readonly arrow: ArrowDirection;
}

// An inert solid square — neither a clue nor an input.
export interface BlockCell {
  readonly kind: 'block';
  readonly position: Position;
}

export type Cell = LetterCell | DefinitionCell | BlockCell;
