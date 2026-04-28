// Frontend UI-domain layer. Per ADR-0002 §7, this layer holds pure UI
// domain types (e.g. GridView, CellState) with zero dependencies on
// application, infrastructure, or vendor SDKs.
export type {
  ArrowDirection,
  BlockCell,
  Cell,
  DefinitionCell,
  DefinitionClue,
  HorizontalArrow,
  LetterCell,
  Position,
  Puzzle,
  VerticalArrow,
} from './puzzle';
export { SAMPLE_PUZZLE } from './puzzle';
