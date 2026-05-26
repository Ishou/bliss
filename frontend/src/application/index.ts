// Frontend application layer. Per ADR-0002 §7, this layer holds use-cases
// and ports orchestrating domain types and infrastructure ports. It
// depends on `domain/` only; concrete adapters live in `infrastructure/`.
export type {
  DailySummariesPage,
  DailySummary,
  FilledCellInput,
  HintErrorKind,
  HintResult,
  IncorrectCell,
  ListDailySummariesOptions,
  PuzzleRepository,
  PuzzleSolver,
  ValidationResult,
} from './puzzle';
export { HintRequestError } from './puzzle';
export type {
  AuthClient,
  GetMeResult,
  LinkedProvider,
  WhoAmIResult,
} from './auth';
export { InvalidDisplayNameError } from './auth';
export { messageForApiError } from './errors';
