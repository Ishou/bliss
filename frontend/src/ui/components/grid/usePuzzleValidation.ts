import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  normalizeAnswerLetter,
  type LetterCell,
  type Puzzle,
} from '@/domain';
import type {
  FilledCellInput,
  PuzzleSolver,
} from '@/application';

// Server-backed validation for the solo `Vérifier` flow. Replaces the
// pre-#218 word-level local validator: the canonical solution no longer
// travels on the wire (`LetterCell.letter` was stripped in PR #218), so
// the only authoritative answer is `POST /v1/puzzles/{id}/validate`.
//
// Behavior:
//   * `verify()` reads each letter cell's value from the DOM (the
//     uncontrolled-input contract per ADR-0002 §4 means we never lift
//     keystrokes into React state), normalizes via
//     `normalizeAnswerLetter`, drops blanks, and POSTs the rest.
//   * On `solved: true` every letter position moves into `validated` —
//     the route's `isComplete` guard flips, the `Vérifier` button
//     disables, and cells lock via the existing `validatedPositions`
//     prop on `<Grid>`.
//   * On `incorrectCells` the listed positions go into `errors` and
//     are cleared 200 ms later (matches the existing shake duration).
//     The server lumps wrong-letter and unfilled-letter into a single
//     set; we render both as the same error shake — the player's
//     mental model is "this attempt isn't right yet".
//   * Reset on puzzle reference change (route loader returns a fresh
//     `Puzzle` after `router.invalidate()`).

const ERROR_REVERT_MS = 200;

const positionKey = (row: number, col: number): string => `${row},${col}`;

export interface PuzzleValidationState {
  readonly validated: ReadonlySet<string>;
  readonly errors: ReadonlySet<string>;
  readonly announce: string;
  readonly verify: () => void;
  readonly pending: boolean;
  readonly totalLetterCells: number;
}

export function usePuzzleValidation(
  puzzle: Puzzle,
  solver: PuzzleSolver,
): PuzzleValidationState {
  const [validated, setValidated] = useState<ReadonlySet<string>>(() => new Set());
  const [errors, setErrors] = useState<ReadonlySet<string>>(() => new Set());
  const [announce, setAnnounce] = useState<string>('');
  const [pending, setPending] = useState<boolean>(false);

  const letterCells = useMemo<readonly LetterCell[]>(
    () => puzzle.cells.filter((c): c is LetterCell => c.kind === 'letter'),
    [puzzle.cells],
  );

  // Reset on puzzle swap. Reference identity is enough — the loader
  // returns a fresh `Puzzle` object on `router.invalidate()`.
  useEffect(() => {
    setValidated(new Set());
    setErrors(new Set());
    setAnnounce('');
  }, [puzzle]);

  const errorTimerRef = useRef<number | null>(null);
  // Track in-flight requests so a stale reply (e.g. user pressed
  // Vérifier twice) cannot resurrect cleared errors.
  const requestSeqRef = useRef(0);

  // Cleanup on unmount.
  useEffect(() => {
    return () => {
      if (errorTimerRef.current !== null) {
        window.clearTimeout(errorTimerRef.current);
        errorTimerRef.current = null;
      }
    };
  }, []);

  const verify = useCallback(() => {
    const filled: FilledCellInput[] = [];
    for (const cell of letterCells) {
      const input = document.querySelector<HTMLInputElement>(
        `input[data-cell-kind="letter"][data-row="${cell.position.row}"][data-col="${cell.position.col}"]`,
      );
      const normalized = normalizeAnswerLetter(input?.value ?? '');
      if (!normalized) continue;
      filled.push({
        row: cell.position.row,
        column: cell.position.col,
        letter: normalized,
      });
    }

    const seq = ++requestSeqRef.current;
    setPending(true);
    void solver
      .validate(puzzle.id, filled)
      .then((result) => {
        if (seq !== requestSeqRef.current) return;
        if (result.solved) {
          const next = new Set<string>();
          for (const cell of letterCells) {
            next.add(positionKey(cell.position.row, cell.position.col));
          }
          setValidated(next);
          setErrors(new Set());
          setAnnounce('Grille terminée');
          return;
        }
        const nextErrors = new Set<string>();
        for (const pos of result.incorrectCells) {
          nextErrors.add(positionKey(pos.row, pos.column));
        }
        setErrors(nextErrors);
        // Lock cells the user submitted that did NOT come back in
        // incorrectCells — those are correct. Without this, Vérifier
        // shakes the wrong cells and leaves the typed-correctly cells
        // in a neutral state, which is what the user sees as "the
        // word I typed correctly is still not green". The merge with
        // `prev` preserves anything previously locked (auto-validation
        // from useWordAutoValidation, an earlier Vérifier, etc.).
        setValidated((prev) => {
          let changed = false;
          const next = new Set(prev);
          for (const cell of filled) {
            const key = positionKey(cell.row, cell.column);
            if (nextErrors.has(key)) continue;
            if (!next.has(key)) {
              next.add(key);
              changed = true;
            }
          }
          return changed ? next : prev;
        });
        setAnnounce(
          nextErrors.size === 1
            ? '1 case à corriger'
            : `${nextErrors.size} cases à corriger`,
        );
        if (errorTimerRef.current !== null) {
          window.clearTimeout(errorTimerRef.current);
        }
        errorTimerRef.current = window.setTimeout(() => {
          errorTimerRef.current = null;
          setErrors(new Set());
        }, ERROR_REVERT_MS);
      })
      .catch(() => {
        if (seq !== requestSeqRef.current) return;
        setAnnounce('Vérification impossible');
      })
      .finally(() => {
        if (seq !== requestSeqRef.current) return;
        setPending(false);
      });
  }, [letterCells, puzzle.id, solver]);

  return {
    validated,
    errors,
    announce,
    verify,
    pending,
    totalLetterCells: letterCells.length,
  };
}
