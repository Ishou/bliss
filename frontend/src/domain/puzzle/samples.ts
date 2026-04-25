import type { ArrowDirection, Cell, DefinitionClue } from './Cell';
import type { Puzzle } from './Puzzle';

// Hand-crafted 5×5 fixture used while the puzzle API is in flight. The
// design is *fully interlocking*: every contiguous run of letter cells
// of length ≥ 2, in any direction, spells a real French word. PR #26's
// earlier 5×5 satisfied "no orphan letter cells" but its columns read
// LEMAR / UTEMU / NERIE — a real product gap that this puzzle closes.
//
// Two definition cells stack a horizontal and a vertical clue (S₁, S₂),
// exercising the multi-clue capability ADR-0005 §3a now allows.
//
// Visual layout (S = stacked def cell, D = single-arrow def cell):
//
//      col0          col1     col2   col3   col4
//   ┌─────────────┬────────┬──────┬──────┬──────┐
// 0 │ S₁ →↓      │  M     │  A   │  I   │  S   │
//   ├─────────────┼────────┼──────┼──────┼──────┤
// 1 │  A          │  ▓     │ D₅↓  │ D₆↓  │ D₇↓  │
//   ├─────────────┼────────┼──────┼──────┼──────┤
// 2 │  S          │ S₂ →↓ │  M   │  U   │  R   │
//   ├─────────────┼────────┼──────┼──────┼──────┤
// 3 │ D₃ →       │  A     │  I   │  R   │  E   │
//   ├─────────────┼────────┼──────┼──────┼──────┤
// 4 │ D₄ →       │  U     │  S   │  E   │  R   │
//   └─────────────┴────────┴──────┴──────┴──────┘
//
// Clues — each with answer cells listed as (row, col):
//
//   S₁ at (0,0):
//     →  "Conjonction d'opposition" MAIS  (0,1) (0,2) (0,3) (0,4)
//     ↓  "Verbe avoir, 2ᵉ personne" AS    (1,0) (2,0)
//   D₃ at (3,0)  →  "Surface, zone"      AIRE  (3,1) (3,2) (3,3) (3,4)
//   D₄ at (4,0)  →  "Se servir de"       USER  (4,1) (4,2) (4,3) (4,4)
//   D₅ at (1,2)  ↓  "Placé, déposé"      MIS   (2,2) (3,2) (4,2)
//   D₆ at (1,3)  ↓  "Auroch, bovin éteint" URE (2,3) (3,3) (4,3)
//   D₇ at (1,4)  ↓  "Train régional francilien" RER (2,4) (3,4) (4,4)
//   S₂ at (2,1):
//     →  "Cloison verticale"     MUR   (2,2) (2,3) (2,4)
//     ↓  "Contraction « à le »"  AU    (3,1) (4,1)
//
// Letter-run audit — every contiguous run of length ≥ 2:
//   row 0 cols 1-4: M-A-I-S = MAIS  (S₁ →)
//   row 2 cols 2-4: M-U-R    = MUR   (S₂ →)
//   row 3 cols 1-4: A-I-R-E  = AIRE  (D₃ →)
//   row 4 cols 1-4: U-S-E-R  = USER  (D₄ →)
//   col 0 rows 1-2: A-S      = AS    (S₁ ↓)
//   col 1 rows 3-4: A-U      = AU    (S₂ ↓)
//   col 2 rows 2-4: M-I-S    = MIS   (D₅ ↓)
//   col 3 rows 2-4: U-R-E    = URE   (D₆ ↓)
//   col 4 rows 2-4: R-E-R    = RER   (D₇ ↓)
//
//   17 letter cells. 7 definition cells (2 stacked, 5 single). 1 block
//   at (1,1) — the dead corner left by stacking S₂ inside the grid.
const L = (row: number, col: number, answer: string): Cell =>
  ({ kind: 'letter', position: { row, col }, answer, entry: '' });
const D1 = (row: number, col: number, text: string, arrow: ArrowDirection): Cell =>
  ({ kind: 'definition', position: { row, col }, clues: [{ text, arrow }] });
const D2 = (row: number, col: number, right: string, down: string): Cell => ({
  kind: 'definition',
  position: { row, col },
  clues: [
    { text: right, arrow: 'right' } as DefinitionClue & { arrow: 'right' },
    { text: down, arrow: 'down' } as DefinitionClue & { arrow: 'down' },
  ] as const,
});
const B = (row: number, col: number): Cell =>
  ({ kind: 'block', position: { row, col } });

const cells: readonly Cell[] = [
  // Row 0 — stacked corner clue + MAIS letters
  D2(0, 0, 'Conjonction d’opposition', 'Verbe avoir, 2ᵉ pers.'),
  L(0, 1, 'M'), L(0, 2, 'A'), L(0, 3, 'I'), L(0, 4, 'S'),
  // Row 1 — col-0 letter (A) + a block at (1,1) + three vertical clues
  L(1, 0, 'A'),
  B(1, 1),
  D1(1, 2, 'Placé, déposé', 'down'),
  D1(1, 3, 'Auroch, bovin éteint', 'down'),
  D1(1, 4, 'Train régional francilien', 'down'),
  // Row 2 — col-0 letter (S), interior stacked clue, MUR letters
  L(2, 0, 'S'),
  D2(2, 1, 'Cloison verticale', 'Contraction « à le »'),
  L(2, 2, 'M'), L(2, 3, 'U'), L(2, 4, 'R'),
  // Row 3 — single right clue + AIRE letters
  D1(3, 0, 'Surface, zone', 'right'),
  L(3, 1, 'A'), L(3, 2, 'I'), L(3, 3, 'R'), L(3, 4, 'E'),
  // Row 4 — single right clue + USER letters
  D1(4, 0, 'Se servir de', 'right'),
  L(4, 1, 'U'), L(4, 2, 'S'), L(4, 3, 'E'), L(4, 4, 'R'),
];

export const SAMPLE_PUZZLE: Puzzle = {
  id: 'sample-fr-5x5',
  title: 'Mots fléchés — démo',
  language: 'fr',
  width: 5,
  height: 5,
  cells,
};
