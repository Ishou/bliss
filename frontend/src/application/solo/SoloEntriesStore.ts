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
  clearForPuzzle(puzzleId: string): void;
}
