// Public surface of the puzzle domain. Only types and pure data are
// exported; no React, DOM, or framework imports per ADR-0002 §7.
export type { Position } from './Position';
export type {
  ArrowDirection,
  BlockCell,
  Cell,
  DefinitionCell,
  DefinitionClue,
  HorizontalArrow,
  LetterCell,
  VerticalArrow,
} from './Cell';
export type { Difficulty, Puzzle } from './Puzzle';
export { SAMPLE_PUZZLE } from './samples';
export { normalizeAnswerLetter } from './letterNormalize';
