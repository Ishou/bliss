// Port for persisting the player's typed letters in solo mode (v1).
//
// The home route uses `load` once on mount to seed the Grid's
// `initialEntries`, fires `save` on every cell change, and calls
// `clearForPuzzle` from the "Actualiser" CTA before invalidating the
// router. Implemented by the `localStorageSolo` adapter in
// `infrastructure/`.
//
// Port lives in `application/` (not `domain/`) because solo-mode entry
// storage is a use-case concern: the domain `Puzzle` shape doesn't know
// about per-session UI progress.

export interface SoloEntry {
  readonly row: number;
  readonly column: number;
  readonly letter: string;
}

export interface SoloEntriesStore {
  load(puzzleId: string): ReadonlyArray<SoloEntry>;
  save(puzzleId: string, row: number, column: number, letter: string | null): void;
  clearForPuzzle(puzzleId: string): void;
}
