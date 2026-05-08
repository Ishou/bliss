// Application-layer port for the two server-authoritative puzzle
// operations introduced in PR #218: full-grid validation and the
// per-puzzle hint budget. Concrete adapters live in `infrastructure/`
// and are wired into the router context by the composition root, so
// `ui/` depends on this port (not the HTTP client) per ADR-0002 §7.
//
// The position field name follows the wire (`column`, not the domain's
// abbreviated `col`) — the adapter forwards directly with no rename, and
// the result mirrors the OpenAPI `Position` shape so we don't introduce
// an extra translation hop in the hot validation path.

export interface FilledCellInput {
  readonly row: number;
  readonly column: number;
  /** A single uppercase A–Z letter; cleared cells must be omitted. */
  readonly letter: string;
}

export interface IncorrectCell {
  readonly row: number;
  readonly column: number;
}

export interface ValidationResult {
  readonly solved: boolean;
  /** Includes both wrong-letter AND unfilled cells; empty iff `solved`. */
  readonly incorrectCells: ReadonlyArray<IncorrectCell>;
}

export interface HintResult {
  /** Server-normalized form of the requested word (NFC, lowercased). */
  readonly word: string;
  /** True iff the word is a real French lemma the server recognises. */
  readonly exists: boolean;
  /** Remaining budget after this call; `0` means the next call 429s. */
  readonly hintsRemaining: number;
}

export type HintErrorKind =
  | 'budget-exhausted'
  | 'invalid-word'
  | 'transient';

// Typed error so the UI can branch on `err.kind` instead of regexing
// `Error.message`. `hintsRemaining` is set when the server reported it
// (always `0` for `budget-exhausted`).
export class HintRequestError extends Error {
  readonly kind: HintErrorKind;
  readonly hintsRemaining: number | null;
  constructor(kind: HintErrorKind, hintsRemaining: number | null, message: string) {
    super(message);
    this.kind = kind;
    this.hintsRemaining = hintsRemaining;
    this.name = 'HintRequestError';
  }
}

export interface PuzzleSolver {
  /**
   * Submit the player's filled cells for server-side validation. Cleared
   * cells must be absent from `filledCells` (do not send `letter: null`).
   */
  validate(
    puzzleId: string,
    filledCells: ReadonlyArray<FilledCellInput>,
  ): Promise<ValidationResult>;

  /**
   * Spend one hint credit asking whether `word` is a real lemma. Throws
   * `HintRequestError` on every documented 4xx (budget-exhausted,
   * invalid-word) and on transient/network failures.
   */
  requestHint(puzzleId: string, word: string): Promise<HintResult>;
}
