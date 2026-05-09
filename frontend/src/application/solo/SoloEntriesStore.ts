// In application/ (not domain/): entry storage is a use-case concern, not a domain invariant.

export interface SoloEntry {
  readonly row: number;
  readonly column: number;
  readonly letter: string;
}

export interface SoloLockedCell {
  readonly row: number;
  readonly column: number;
}

export interface SoloEntriesStore {
  load(puzzleId: string): ReadonlyArray<SoloEntry>;
  save(puzzleId: string, row: number, column: number, letter: string | null): void;
  loadLockedCells(puzzleId: string): ReadonlyArray<SoloLockedCell>;
  lockCell(puzzleId: string, row: number, column: number): void;
  // Number of hints already consumed on this puzzle (0 when none).
  // The hint counter is ephemeral React state; persisting the running
  // tally per-puzzle lets a page reload restore `hintsRemaining` from
  // `hintsAllowed - loadHintsUsed(puzzleId)`.
  loadHintsUsed(puzzleId: string): number;
  recordHintUsed(puzzleId: string): void;
  clearForPuzzle(puzzleId: string): void;
}
