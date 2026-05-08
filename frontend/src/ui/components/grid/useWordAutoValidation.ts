import { useCallback, useEffect, useRef, useState } from 'react';
import {
  normalizeAnswerLetter,
  type LetterCell,
  type Position,
  type Puzzle,
} from '@/domain';
import type { FilledCellInput, PuzzleSolver } from '@/application';
import { wordRange } from './wordRange';

// Solo auto-validation: when a typed letter completes a word (every
// cell in that word holds a normalized letter), POST the whole grid to
// `validate` and lock the word's cells if none of its positions came
// back in `incorrectCells`. Wrong words emit no UI signal at all — the
// hook drops them silently per the product decision (incorrect words
// must be visually indistinguishable from in-progress ones).
//
// Why a per-keystroke fetch isn't a problem here: the solo validate
// endpoint is stateless and cheap; we coalesce in-flight requests by
// word key so a steady stream of typing collapses to at most one
// request per completed word. The hook is layered ON TOP of the
// existing manual-Vérifier `usePuzzleValidation`; the route merges the
// two `validated` sets before passing to `<Grid>`.

const positionKey = (row: number, col: number): string => `${row},${col}`;
const wordKey = (positions: ReadonlyArray<Position>): string =>
  positions.map((p) => positionKey(p.row, p.col)).join('|');

export interface WordAutoValidationState {
  readonly validated: ReadonlySet<string>;
  readonly onCellFilled: (position: Position, direction: 'across' | 'down') => void;
}

export function useWordAutoValidation(
  puzzle: Puzzle,
  solver: PuzzleSolver,
): WordAutoValidationState {
  const [validated, setValidated] = useState<ReadonlySet<string>>(() => new Set());

  // Reset on puzzle swap. Reference identity is enough — the loader
  // returns a fresh `Puzzle` object on `router.invalidate()`.
  useEffect(() => {
    setValidated(new Set());
  }, [puzzle]);

  // Track in-flight word checks so we don't pile up duplicate requests
  // for the same word while one is on the wire.
  const inFlightRef = useRef(new Set<string>());

  const onCellFilled = useCallback(
    (position: Position, direction: 'across' | 'down') => {
      // Both directions matter: typing the last letter of an across
      // word might also complete a perpendicular down word that this
      // cell sits on. Probe both ranges every fill.
      const candidates: Position[][] = [];
      const across = wordRange(puzzle, position, 'across');
      if (across.length >= 2) candidates.push([...across]);
      const down = wordRange(puzzle, position, 'down');
      if (down.length >= 2) candidates.push([...down]);
      // Order across first when the typed direction was across (more
      // likely to be the "primary" word the player just finished),
      // otherwise down — purely a UX nicety for any future ordering
      // bugs; the validate request lumps them together regardless.
      if (direction === 'down') candidates.reverse();

      // Filter to fully-filled candidate words, reading the latest DOM
      // values directly (the uncontrolled-input contract per ADR-0002
      // §4 means letters never live in React state).
      const fullyFilled: Position[][] = [];
      for (const word of candidates) {
        const allFilled = word.every((pos) => {
          const input = document.querySelector<HTMLInputElement>(
            `input[data-cell-kind="letter"][data-row="${pos.row}"][data-col="${pos.col}"]`,
          );
          return !!normalizeAnswerLetter(input?.value ?? '');
        });
        if (!allFilled) continue;
        const key = wordKey(word);
        if (inFlightRef.current.has(key)) continue;
        // Skip only if the WHOLE word is already validated — a perpendicular
        // word crossing a previously-locked one shares one cell with that
        // lock, but its other cells still need to be checked. The earlier
        // `validated.has(word[0])` short-circuit silently dropped every
        // word that crossed a lock at its starting cell, which is the
        // user-reported "validation does not happen when crossing an
        // already validated word".
        const wordKeys = word.map((p) => positionKey(p.row, p.col));
        if (wordKeys.every((k) => validated.has(k))) continue;
        fullyFilled.push(word);
      }
      if (fullyFilled.length === 0) return;

      // Collect EVERY filled cell on the grid, not just the candidate
      // words. The solo validate endpoint compares submitted letters to
      // the canonical solution; cells absent from the request are
      // reported in `incorrectCells`. A word is correct iff none of
      // its positions appear in the response's `incorrectCells`.
      const filled: FilledCellInput[] = [];
      const letterCells: LetterCell[] = puzzle.cells.filter(
        (c): c is LetterCell => c.kind === 'letter',
      );
      for (const cell of letterCells) {
        const input = document.querySelector<HTMLInputElement>(
          `input[data-cell-kind="letter"][data-row="${cell.position.row}"][data-col="${cell.position.col}"]`,
        );
        const letter = normalizeAnswerLetter(input?.value ?? '');
        if (!letter) continue;
        filled.push({
          row: cell.position.row,
          column: cell.position.col,
          letter,
        });
      }

      for (const word of fullyFilled) {
        inFlightRef.current.add(wordKey(word));
      }
      void solver
        .validate(puzzle.id, filled)
        .then((result) => {
          // Build a set of incorrect position keys for cheap lookup.
          const incorrect = new Set<string>();
          for (const pos of result.incorrectCells) {
            incorrect.add(positionKey(pos.row, pos.column));
          }
          setValidated((prev) => {
            let changed = false;
            const next = new Set(prev);
            for (const word of fullyFilled) {
              const wordKeys = word.map((p) => positionKey(p.row, p.col));
              const allCorrect = wordKeys.every((k) => !incorrect.has(k));
              if (!allCorrect) continue;
              for (const k of wordKeys) {
                if (!next.has(k)) {
                  next.add(k);
                  changed = true;
                }
              }
            }
            return changed ? next : prev;
          });
        })
        // Wrong words drop silently — incorrect fills are visually
        // indistinguishable from in-progress ones per the product
        // decision. Network failures behave the same way: the user
        // can still try Vérifier for the explicit signal.
        .catch(() => {})
        .finally(() => {
          for (const word of fullyFilled) {
            inFlightRef.current.delete(wordKey(word));
          }
        });
    },
    [puzzle, solver, validated],
  );

  return { validated, onCellFilled };
}
