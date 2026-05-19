import type { ArrowDirection, Cell, DefinitionClue } from './Cell';
import type { Puzzle } from './Puzzle';

// Hand-crafted 5×5 fixture used while the puzzle API is in flight. The
// design is *fully interlocking*: every contiguous run of letter cells
// of length ≥ 2, in any direction, spells a real, common French word —
// a word a French speaker without puzzle hobbies will recognise without
// reaching for a dictionary. The earlier 5×5 (PR #31) was structurally
// fully-interlocking but leaned on RER (a Parisian acronym, not a noun)
// and URE (auroch — pure dictionary obscurity); both have been swapped
// out for everyday words. Two definition cells stack a horizontal and a
// vertical clue (S₁, S₂), exercising the multi-clue capability ADR-0005
// §3a allows.
//
// Visual layout (S = stacked def cell, D = single-arrow def cell):
//
//      col0          col1     col2   col3   col4
//   ┌─────────────┬────────┬──────┬──────┬──────┐
// 0 │ S₁ →↓      │  M     │  A   │  I   │  S   │
//   ├─────────────┼────────┼──────┼──────┼──────┤
// 1 │  A          │  ▓     │ D₅↓  │ D₆↓  │ D₇↓  │
//   ├─────────────┼────────┼──────┼──────┼──────┤
// 2 │  S          │ S₂ →↓ │  P   │  U   │  S   │
//   ├─────────────┼────────┼──────┼──────┼──────┤
// 3 │ D₃ →       │  D     │  O   │  S   │  E   │
//   ├─────────────┼────────┼──────┼──────┼──────┤
// 4 │ D₄ →       │  E     │  T   │  E   │  S   │
//   └─────────────┴────────┴──────┴──────┴──────┘
//
// Clues — each with answer cells listed as (row, col):
//
//   S₁ at (0,0):
//     →  "Conjonction d'opposition" MAIS  (0,1) (0,2) (0,3) (0,4)
//     ↓  "Verbe avoir, 2ᵉ personne" AS    (1,0) (2,0)
//   D₃ at (3,0)  →  "Quantité de médicament"  DOSE  (3,1) (3,2) (3,3) (3,4)
//   D₄ at (4,0)  →  "Verbe être, 2ᵉ pers. pl." ETES  (4,1) (4,2) (4,3) (4,4)
//   D₅ at (1,2)  ↓  "Récipient en terre cuite" POT   (2,2) (3,2) (4,2)
//   D₆ at (1,3)  ↓  "Se sert de, emploie"      USE   (2,3) (3,3) (4,3)
//   D₇ at (1,4)  ↓  "Pluriel de « son »"       SES   (2,4) (3,4) (4,4)
//   S₂ at (2,1):
//     →  "Liquide d'une plaie infectée" PUS  (2,2) (2,3) (2,4)
//     ↓  "Préposition d'appartenance"   DE   (3,1) (4,1)
//
// Letter-run audit — every contiguous run of length ≥ 2 with a one-line
// "common French" justification (a French speaker not into puzzles
// recognises each without a dictionary):
//   row 0 cols 1-4: M-A-I-S = MAIS  — conjunction "but", daily speech
//   row 2 cols 2-4: P-U-S    = PUS   — pus (médical, universel)
//   row 3 cols 1-4: D-O-S-E  = DOSE  — dose, posologie
//   row 4 cols 1-4: E-T-E-S  = ETES  — « vous êtes », auxiliaire être
//   col 0 rows 1-2: A-S      = AS    — « tu as », auxiliaire avoir
//   col 1 rows 3-4: D-E      = DE    — préposition la plus fréquente
//   col 2 rows 2-4: P-O-T    = POT   — pot, récipient
//   col 3 rows 2-4: U-S-E    = USE   — « il use », forme de « user »
//   col 4 rows 2-4: S-E-S    = SES   — pluriel de « son », possessif
//
//   17 letter cells. 7 definition cells (2 stacked, 5 single). 1 block
//   at (1,1) — the dead corner left by stacking S₂ inside the grid.
// Canonical letter is no longer carried by the domain (PR #218 stripped
// it from the wire). The cell constructor keeps the same shape it had
// in v1 minus the `answer` field; the row layouts in the comment block
// above still document which letter belongs in each slot.
const L = (row: number, col: number): Cell =>
  ({ kind: 'letter', position: { row, col }, entry: '' });
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
  L(0, 1), L(0, 2), L(0, 3), L(0, 4),
  // Row 1 — col-0 letter (A) + a block at (1,1) + three vertical clues
  L(1, 0),
  B(1, 1),
  D1(1, 2, 'Récipient en terre cuite', 'down'),
  D1(1, 3, 'Se sert de, emploie', 'down'),
  D1(1, 4, 'Pluriel de « son »', 'down'),
  // Row 2 — col-0 letter (S), interior stacked clue, PUS letters
  L(2, 0),
  D2(2, 1, 'Liquide d’une plaie infectée', 'Préposition d’appartenance'),
  L(2, 2), L(2, 3), L(2, 4),
  // Row 3 — single right clue + DOSE letters
  D1(3, 0, 'Quantité de médicament', 'right'),
  L(3, 1), L(3, 2), L(3, 3), L(3, 4),
  // Row 4 — single right clue + ETES letters
  D1(4, 0, 'Verbe être, 2ᵉ pers. pl.', 'right'),
  L(4, 1), L(4, 2), L(4, 3), L(4, 4),
];

export const SAMPLE_PUZZLE: Puzzle = {
  id: 'sample-fr-5x5',
  title: 'Mots fléchés — démo',
  language: 'fr',
  width: 5,
  height: 5,
  hintsAllowed: 3,
  hintsRemaining: 3,
  cells,
};
