import type { ArrowDirection, Cell } from './Cell';
import type { Puzzle } from './Puzzle';

// Hand-crafted 5×5 fixture used while the puzzle API is in flight. The
// shape mimics a real *mots fléchés*: every letter cell sits on at least
// one clue's answer path. Definition cells emit a single arrow (right or
// down) per ADR-0005's v1 simplification — no diagonal-split cells.
//
// Visual layout (D = definition, letters shown as themselves):
//
//     col0   col1  col2  col3  col4
//   ┌──────┬─────┬─────┬─────┬─────┐
// 0 │ D→   │  L  │  U  │  N  │  E  │
//   ├──────┼─────┼─────┼─────┼─────┤
// 1 │ D→   │  E  │  T  │  E  │ D↓  │
//   ├──────┼─────┼─────┼─────┼─────┤
// 2 │ D→   │  M  │  E  │  R  │  S  │
//   ├──────┼─────┼─────┼─────┼─────┤
// 3 │ D→   │  A  │  M  │  I  │  E  │
//   ├──────┼─────┼─────┼─────┼─────┤
// 4 │ D→   │  R  │  U  │  E  │  S  │
//   └──────┴─────┴─────┴─────┴─────┘
//
// Clues — answer cells listed as (row, col):
//
//   (0,0) →  "Astre nocturne"        LUNE  : (0,1) (0,2) (0,3) (0,4)
//   (1,0) →  "Saison chaude"         ETE   : (1,1) (1,2) (1,3)
//   (1,4) ↓  "Adjectifs possessifs"  SES   : (2,4) (3,4) (4,4)
//   (2,0) →  "Étendues salées"       MERS  : (2,1) (2,2) (2,3) (2,4)
//   (3,0) →  "Compagne fidèle"       AMIE  : (3,1) (3,2) (3,3) (3,4)
//   (4,0) →  "Voies urbaines"        RUES  : (4,1) (4,2) (4,3) (4,4)
//
// Coverage audit — every letter cell appears in at least one clue path:
//   row 0: (0,1) (0,2) (0,3) (0,4)              ← LUNE
//   row 1: (1,1) (1,2) (1,3)                    ← ETE
//   row 2: (2,1) (2,2) (2,3) (2,4)              ← MERS, plus (2,4) ∈ SES
//   row 3: (3,1) (3,2) (3,3) (3,4)              ← AMIE, plus (3,4) ∈ SES
//   row 4: (4,1) (4,2) (4,3) (4,4)              ← RUES, plus (4,4) ∈ SES
//
//   19 letter cells, all covered. 6 definition cells. 0 block cells.
const L = (row: number, col: number, answer: string): Cell =>
  ({ kind: 'letter', position: { row, col }, answer, entry: '' });
const D = (row: number, col: number, text: string, arrow: ArrowDirection): Cell =>
  ({ kind: 'definition', position: { row, col }, text, arrow });

const cells: readonly Cell[] = [
  // Row 0 — "Astre nocturne" → LUNE
  D(0, 0, 'Astre nocturne', 'right'),
  L(0, 1, 'L'), L(0, 2, 'U'), L(0, 3, 'N'), L(0, 4, 'E'),
  // Row 1 — "Saison chaude" → ETE, then a vertical clue at (1,4)
  D(1, 0, 'Saison chaude', 'right'),
  L(1, 1, 'E'), L(1, 2, 'T'), L(1, 3, 'E'),
  D(1, 4, 'Adjectifs possessifs', 'down'),
  // Row 2 — "Étendues salées" → MERS
  D(2, 0, 'Étendues salées', 'right'),
  L(2, 1, 'M'), L(2, 2, 'E'), L(2, 3, 'R'), L(2, 4, 'S'),
  // Row 3 — "Compagne fidèle" → AMIE
  D(3, 0, 'Compagne fidèle', 'right'),
  L(3, 1, 'A'), L(3, 2, 'M'), L(3, 3, 'I'), L(3, 4, 'E'),
  // Row 4 — "Voies urbaines" → RUES
  D(4, 0, 'Voies urbaines', 'right'),
  L(4, 1, 'R'), L(4, 2, 'U'), L(4, 3, 'E'), L(4, 4, 'S'),
];

export const SAMPLE_PUZZLE: Puzzle = {
  id: 'sample-fr-5x5',
  title: 'Mots fléchés — démo',
  language: 'fr',
  width: 5,
  height: 5,
  cells,
};
