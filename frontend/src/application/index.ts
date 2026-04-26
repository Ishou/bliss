// Frontend application layer. Per ADR-0002 §7, this layer holds use-cases
// and ports orchestrating domain types and infrastructure ports. It
// depends on `domain/` only; concrete adapters live in `infrastructure/`.
export type { PuzzleRepository } from './puzzle';
