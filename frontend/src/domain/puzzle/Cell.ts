import type { Position } from './Position';

// Direction in which a definition's answer flows. `right` means the answer
// occupies the cells immediately to the right of the definition cell;
// `down` means it occupies the cells immediately below. v1 deliberately
// excludes diagonal-split cells; the stacked variant below is the only
// way to fit two clues into a single cell.
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

// A single clue inside a definition cell: the prose text the player reads
// and the arrow that anchors its answer path on the grid.
export interface DefinitionClue {
  readonly text: string;
  readonly arrow: ArrowDirection;
}

// A clue cell. Carries one or two clues per ADR-0005 §3a. When two are
// present, the invariant is `clues[0].arrow === 'right'` and
// `clues[1].arrow === 'down'`: real *mots fléchés* always render the
// horizontal clue above the vertical one, and pinning the order in the
// type means the renderer never has to re-sort. Any other shape is a
// domain bug; the architecture tests guard against it.
export interface DefinitionCell {
  readonly kind: 'definition';
  readonly position: Position;
  readonly clues:
    | readonly [DefinitionClue]
    | readonly [DefinitionClue & { arrow: 'right' }, DefinitionClue & { arrow: 'down' }];
}

// An inert solid square — neither a clue nor an input.
export interface BlockCell {
  readonly kind: 'block';
  readonly position: Position;
}

export type Cell = LetterCell | DefinitionCell | BlockCell;
