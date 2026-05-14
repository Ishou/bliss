import type { Puzzle } from '@/domain';

// Application-layer port for loading a puzzle. Adapters live in
// `infrastructure/` (HTTP today, MSW or IndexedDB later); the route
// loader receives an instance through router context so `ui/` keeps
// zero `infrastructure/` imports per ADR-0002 §7. Failures surface as
// rejected promises with an `Error.message` the route's
// `errorComponent` renders verbatim.
export interface PuzzleRepository {
  fetchById(puzzleId: string): Promise<Puzzle>;
  // Daily-grid endpoint. `date` is an ISO calendar string `YYYY-MM-DD`;
  // the server falls back to today's UTC date when the parameter is
  // omitted. The Accueil and Grille route loaders both call this so the
  // two surfaces converge on the same canonical puzzleId for the day.
  //
  // Resolves to `null` when the daily worker has not yet produced a
  // row for the requested date (404, RFC 7807 type
  // `https://bliss.example/errors/no-daily-puzzle` per ADR-0042); the
  // route component renders a graceful "daily not yet available"
  // message instead of an error toast. Other failures (network,
  // 4xx/5xx other than 404) reject as before.
  fetchDaily(date?: string): Promise<Puzzle | null>;
}
