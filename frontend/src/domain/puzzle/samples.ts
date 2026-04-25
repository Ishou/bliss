import type { Cell } from './Cell';
import type { Puzzle } from './Puzzle';

// Hand-crafted 5×5 fixture used while the puzzle API is in flight. The
// content is not meant to be a fully-valid mots fléchés (interlocking
// answers), only to exercise every cell variant the renderer must handle:
// definition cells with both arrow directions, multiple letter cells, and
// at least one block. Will be replaced when the API workstream lands.
//
// Visual layout (D = definition, B = block, letters shown as themselves):
//
//   D→  L  U  N  E
//   D↓  B  A  R  T
//   M   A  R  I  S
//   E   B  L  E  U
//   R   A  R  E  S
//
// The two definitions cue "LUNE" (across, row 0) and "MER" (down,
// column 0, rows 2-4). Other letters are filler.
const cells: readonly Cell[] = [
  { kind: 'definition', position: { row: 0, col: 0 }, text: 'Astre nocturne', arrow: 'right' },
  { kind: 'letter', position: { row: 0, col: 1 }, answer: 'L', entry: '' },
  { kind: 'letter', position: { row: 0, col: 2 }, answer: 'U', entry: '' },
  { kind: 'letter', position: { row: 0, col: 3 }, answer: 'N', entry: '' },
  { kind: 'letter', position: { row: 0, col: 4 }, answer: 'E', entry: '' },

  { kind: 'definition', position: { row: 1, col: 0 }, text: 'Eau salée', arrow: 'down' },
  { kind: 'block', position: { row: 1, col: 1 } },
  { kind: 'letter', position: { row: 1, col: 2 }, answer: 'A', entry: '' },
  { kind: 'letter', position: { row: 1, col: 3 }, answer: 'R', entry: '' },
  { kind: 'letter', position: { row: 1, col: 4 }, answer: 'T', entry: '' },

  { kind: 'letter', position: { row: 2, col: 0 }, answer: 'M', entry: '' },
  { kind: 'letter', position: { row: 2, col: 1 }, answer: 'A', entry: '' },
  { kind: 'letter', position: { row: 2, col: 2 }, answer: 'R', entry: '' },
  { kind: 'letter', position: { row: 2, col: 3 }, answer: 'I', entry: '' },
  { kind: 'letter', position: { row: 2, col: 4 }, answer: 'S', entry: '' },

  { kind: 'letter', position: { row: 3, col: 0 }, answer: 'E', entry: '' },
  { kind: 'letter', position: { row: 3, col: 1 }, answer: 'B', entry: '' },
  { kind: 'letter', position: { row: 3, col: 2 }, answer: 'L', entry: '' },
  { kind: 'letter', position: { row: 3, col: 3 }, answer: 'E', entry: '' },
  { kind: 'letter', position: { row: 3, col: 4 }, answer: 'U', entry: '' },

  { kind: 'letter', position: { row: 4, col: 0 }, answer: 'R', entry: '' },
  { kind: 'letter', position: { row: 4, col: 1 }, answer: 'A', entry: '' },
  { kind: 'letter', position: { row: 4, col: 2 }, answer: 'R', entry: '' },
  { kind: 'letter', position: { row: 4, col: 3 }, answer: 'E', entry: '' },
  { kind: 'letter', position: { row: 4, col: 4 }, answer: 'S', entry: '' },
];

export const SAMPLE_PUZZLE: Puzzle = {
  id: 'sample-fr-5x5',
  title: 'Mots fléchés — démo',
  language: 'fr',
  width: 5,
  height: 5,
  cells,
};
