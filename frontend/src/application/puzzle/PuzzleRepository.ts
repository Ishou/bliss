import type { Puzzle } from '@/domain';

// Application-layer port for loading a puzzle. Adapters live in
// `infrastructure/` (HTTP today, MSW or IndexedDB later); the route
// loader receives an instance through router context so `ui/` keeps
// zero `infrastructure/` imports per ADR-0002 §7. Failures surface as
// rejected promises with an `Error.message` the route's
// `errorComponent` renders verbatim.
export interface PuzzleRepository {
  fetchById(puzzleId: string): Promise<Puzzle>;
  // null when daily worker not yet ready for that date (ADR-0042 / 404); other failures reject.
  fetchDaily(date?: string): Promise<Puzzle | null>;
}
