import type { Puzzle } from '@/domain';

// Thin daily-puzzle summary used by the archive (`/grilles`) UI. Mirrors
// the wire `PuzzleSummary` from the Grid API but lives in the application
// layer so `ui/` never imports from `infrastructure/api/grid/types.ts`.
export interface DailySummary {
  readonly id: string;
  readonly date: string; // ISO YYYY-MM-DD
  readonly gridNumber: number;
  readonly difficulty: string | null;
  readonly totalLetterCells: number;
}

// Calendar-range options for `listDailySummaries`. Both bounds optional;
// the server clamps `from` >= launch anchor and `to` <= today UTC.
export interface ListDailySummariesOptions {
  readonly from?: string;
  readonly to?: string;
}

// Envelope returned by `listDailySummaries`. `hasMore` is `true` when the
// server truncated the range to its 100-item cap; clients page further
// back by re-issuing with `to` set to one day before the oldest item.
export interface DailySummariesPage {
  readonly items: ReadonlyArray<DailySummary>;
  readonly hasMore: boolean;
}

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
  // DESC-by-date list of daily summaries for the archive view.
  listDailySummaries(opts?: ListDailySummariesOptions): Promise<DailySummariesPage>;
}
