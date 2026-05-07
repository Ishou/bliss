import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { LetterCell, Puzzle } from '@/domain';
import { wordRange, type WordDirection } from './wordRange';

// Owner-side state + handlers for the `Vérifier` flow — ADR-0005 §7.
//
// Validation is WORD-LEVEL: cells lock only when their full enclosing
// word is correct, not letter-by-letter. This matches the player's
// expectation ("a half-right word should not freeze me out of the
// other half") and matches the spec's "validation transition (filled
// → validated) — 180 ms, ease-out" intent (the transition is per
// word, not per letter).
//
// Per-word rules:
//   * All letter cells in the word filled AND all match `answer`
//     → every cell of the word moves into `validated` and locks.
//   * Word fully filled, but at least one letter wrong → only the
//     mismatched cells go into `errors` (transient, cleared after the
//     200 ms shake). The word stays unlocked.
//   * Word partially filled → no validation, no errors. The player
//     keeps typing.
//
// A cell at the intersection of two words validates as soon as
// EITHER enclosing word is fully correct, so progress on one axis
// can lock cells on the perpendicular axis automatically. Cells
// without an `answer` field — only the sample puzzles in
// `domain/puzzle/samples.ts` lack one — are skipped silently.
//
// `announce` is a French status string suitable for an `aria-live="polite"`
// region (brief §7 + §8: "validation results should also be announced,
// not only shown").

const ERROR_REVERT_MS = 200;

const positionKey = (row: number, col: number): string => `${row},${col}`;

function normalize(letter: string | null | undefined): string {
  return (letter ?? '').trim().toUpperCase();
}

export interface ValidationState {
  readonly validated: ReadonlySet<string>;
  readonly errors: ReadonlySet<string>;
  readonly announce: string;
  readonly verify: () => void;
  readonly totalLetterCells: number;
}

export function useValidation(puzzle: Puzzle): ValidationState {
  const [validated, setValidated] = useState<ReadonlySet<string>>(() => new Set());
  const [errors, setErrors] = useState<ReadonlySet<string>>(() => new Set());
  const [announce, setAnnounce] = useState<string>('');

  // Reset on puzzle swap (route loader returns a fresh `Puzzle` after
  // `router.invalidate()`, identifying it by reference). Without this
  // the previous puzzle's validated cells would carry over and lock
  // letter cells that haven't been typed yet on the new grid.
  useEffect(() => {
    setValidated(new Set());
    setErrors(new Set());
    setAnnounce('');
  }, [puzzle]);

  // Cache the letter cells (filtered + indexed) so each `verify()` call
  // doesn't re-walk `puzzle.cells` for the answer lookup.
  const letterCells = useMemo<readonly LetterCell[]>(
    () => puzzle.cells.filter((c): c is LetterCell => c.kind === 'letter'),
    [puzzle.cells],
  );

  // Index cells by position for O(1) lookups during word checks.
  const letterByPos = useMemo(() => {
    const m = new Map<string, LetterCell>();
    for (const c of letterCells) m.set(positionKey(c.position.row, c.position.col), c);
    return m;
  }, [letterCells]);

  // The error timer is held in a ref so a second `Vérifier` press during
  // an in-flight shake replaces (rather than races with) the previous
  // revert.
  const errorTimerRef = useRef<number | null>(null);

  const verify = useCallback(() => {
    const nextValidated = new Set(validated);
    const nextErrors = new Set<string>();
    // De-dupe word checks: every cell sits in up to two words (one
    // across, one down). Visiting each word once via its origin key
    // (the position of its first cell + axis) keeps the algorithm
    // O(cells) instead of O(cells × axis).
    const visitedWords = new Set<string>();
    let wordsValidatedThisRun = 0;
    let cellsLockedThisRun = 0;
    let wrongCellCount = 0;

    const checkWord = (
      origin: { row: number; col: number },
      direction: WordDirection,
    ): void => {
      const range = wordRange(puzzle, origin, direction);
      // Single-letter "words" aren't real entries — at least 2 cells
      // are needed for a clue answer.
      if (range.length < 2) return;
      const head = range[0];
      const wordKey = `${direction}@${head.row},${head.col}`;
      if (visitedWords.has(wordKey)) return;
      visitedWords.add(wordKey);

      let allFilled = true;
      let allCorrect = true;
      let answersDefined = true;
      const wrongInThisWord: string[] = [];
      for (const pos of range) {
        const cell = letterByPos.get(positionKey(pos.row, pos.col));
        if (!cell) { answersDefined = false; break; }
        const expected = normalize(cell.answer);
        if (!expected) { answersDefined = false; break; }
        const input = document.querySelector<HTMLInputElement>(
          `input[data-cell-kind="letter"][data-row="${pos.row}"][data-col="${pos.col}"]`,
        );
        const actual = normalize(input?.value);
        if (!actual) {
          allFilled = false;
          allCorrect = false;
        } else if (actual !== expected) {
          allCorrect = false;
          wrongInThisWord.push(positionKey(pos.row, pos.col));
        }
      }
      if (!answersDefined) return;
      if (allFilled && allCorrect) {
        for (const pos of range) {
          const k = positionKey(pos.row, pos.col);
          if (!nextValidated.has(k)) {
            nextValidated.add(k);
            cellsLockedThisRun += 1;
          }
        }
        wordsValidatedThisRun += 1;
      } else if (allFilled) {
        // Filled but at least one letter wrong — shake EVERY cell of
        // the word, not just the mismatched ones. Treats the word as
        // a single guess, matching the player's mental model: "this
        // attempt is wrong" reads cleaner than "these specific cells
        // need fixing", and avoids accidentally pointing at the
        // correct letters as the wrong ones via process of elimination.
        // Validated cells inside the word stay locked (the shake
        // animation runs only when `error: true` and `validated: false`
        // — see Cell.tsx state precedence).
        for (const pos of range) {
          const k = positionKey(pos.row, pos.col);
          if (nextValidated.has(k)) continue;
          nextErrors.add(k);
        }
        wrongCellCount += wrongInThisWord.length;
      }
    };

    // Walk every letter cell, checking each axis. The visited-words
    // guard inside `checkWord` keeps repeated visits cheap.
    for (const cell of letterCells) {
      checkWord(cell.position, 'across');
      checkWord(cell.position, 'down');
    }

    setValidated(nextValidated);
    setErrors(nextErrors);

    // Aria-live message — word-level rather than letter-level so the
    // copy reflects the new validation contract. "X mots validés"
    // is more useful at this level than the old letter count.
    const messageParts: string[] = [];
    if (wordsValidatedThisRun > 0) {
      messageParts.push(
        wordsValidatedThisRun === 1
          ? `1 mot validé · ${cellsLockedThisRun} cases verrouillées`
          : `${wordsValidatedThisRun} mots validés · ${cellsLockedThisRun} cases verrouillées`,
      );
    }
    if (wrongCellCount > 0) {
      messageParts.push(
        wrongCellCount === 1
          ? '1 lettre incorrecte'
          : `${wrongCellCount} lettres incorrectes`,
      );
    }
    if (messageParts.length === 0) {
      messageParts.push('Aucun mot terminé pour le moment');
    } else if (
      wrongCellCount === 0 &&
      nextValidated.size === letterCells.length
    ) {
      messageParts.push('Grille terminée');
    }
    setAnnounce(messageParts.join(' · '));

    // Schedule the error revert. Cleared error set kicks the cell
    // out of its shake state; the player's typed value is left on
    // the <input> so editing resumes immediately.
    if (errorTimerRef.current !== null) {
      window.clearTimeout(errorTimerRef.current);
    }
    if (nextErrors.size > 0) {
      errorTimerRef.current = window.setTimeout(() => {
        errorTimerRef.current = null;
        setErrors(new Set());
      }, ERROR_REVERT_MS);
    }
  }, [letterByPos, letterCells, puzzle, validated]);

  return {
    validated,
    errors,
    announce,
    verify,
    totalLetterCells: letterCells.length,
  };
}
