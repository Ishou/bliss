import type { ArrowDirection, Cell } from './Cell';
import type { Puzzle } from './Puzzle';

// Hand-crafted 5×5 fixture used while the puzzle API is in flight. Only
// exists to exercise every cell variant the renderer handles: definition
// cells with both arrow directions, several letter cells, and at least one
// block. Replaced when the API workstream lands.
//
// Visual layout (D = definition, B = block, letters shown as themselves):
//
//   D→  L  U  N  E
//   D↓  B  A  R  T
//   M   A  R  I  S
//   E   B  L  E  U
//   R   A  R  E  S
const L = (row: number, col: number, answer: string): Cell =>
  ({ kind: 'letter', position: { row, col }, answer, entry: '' });
const D = (row: number, col: number, text: string, arrow: ArrowDirection): Cell =>
  ({ kind: 'definition', position: { row, col }, text, arrow });
const B = (row: number, col: number): Cell =>
  ({ kind: 'block', position: { row, col } });

const cells: readonly Cell[] = [
  D(0, 0, 'Astre nocturne', 'right'),
  L(0, 1, 'L'), L(0, 2, 'U'), L(0, 3, 'N'), L(0, 4, 'E'),
  D(1, 0, 'Eau salée', 'down'), B(1, 1),
  L(1, 2, 'A'), L(1, 3, 'R'), L(1, 4, 'T'),
  L(2, 0, 'M'), L(2, 1, 'A'), L(2, 2, 'R'), L(2, 3, 'I'), L(2, 4, 'S'),
  L(3, 0, 'E'), L(3, 1, 'B'), L(3, 2, 'L'), L(3, 3, 'E'), L(3, 4, 'U'),
  L(4, 0, 'R'), L(4, 1, 'A'), L(4, 2, 'R'), L(4, 3, 'E'), L(4, 4, 'S'),
];

export const SAMPLE_PUZZLE: Puzzle = {
  id: 'sample-fr-5x5',
  title: 'Mots fléchés — démo',
  language: 'fr',
  width: 5,
  height: 5,
  cells,
};
