import { useCallback, useEffect, useRef, useState } from 'react';
import {
  HintRequestError,
  type PuzzleSolver,
} from '@/application';

// Owner-side state for the hint affordance. Spends one credit per
// `request(row, column)` to reveal the canonical letter at that cell;
// the server is authoritative on the running counter, so a 200 always
// overwrites `hintsRemaining`. A 429 (`budget-exhausted`) flips
// `exhausted` and disables the affordance for the rest of this puzzle.
// 400 `invalid-coord` is a stale-focus race (the focused cell got
// updated between click and request); surface as a transient error
// without changing the budget. Reset when the puzzle reference changes.

const RESULT_LINGER_MS = 4_000;

export interface HintLastResult {
  readonly row: number;
  readonly column: number;
  readonly letter: string;
}

export interface HintRequestState {
  readonly hintsRemaining: number;
  readonly exhausted: boolean;
  readonly pending: boolean;
  readonly lastResult: HintLastResult | null;
  readonly errorMessage: string | null;
  readonly request: (row: number, column: number) => void;
}

export function useHintRequest(
  puzzleId: string,
  hintsAllowed: number,
  solver: PuzzleSolver,
  onReveal?: (row: number, column: number, letter: string) => void,
): HintRequestState {
  const [hintsRemaining, setHintsRemaining] = useState<number>(hintsAllowed);
  const [exhausted, setExhausted] = useState<boolean>(false);
  const [pending, setPending] = useState<boolean>(false);
  const [lastResult, setLastResult] = useState<HintLastResult | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const lingerTimerRef = useRef<number | null>(null);
  const requestSeqRef = useRef(0);
  const onRevealRef = useRef(onReveal);
  useEffect(() => {
    onRevealRef.current = onReveal;
  }, [onReveal]);

  // Reset on puzzle change (or hintsAllowed change, which is downstream
  // of the same wire field).
  useEffect(() => {
    setHintsRemaining(hintsAllowed);
    setExhausted(false);
    setPending(false);
    setLastResult(null);
    setErrorMessage(null);
    requestSeqRef.current += 1;
    if (lingerTimerRef.current !== null) {
      window.clearTimeout(lingerTimerRef.current);
      lingerTimerRef.current = null;
    }
  }, [puzzleId, hintsAllowed]);

  // Cleanup on unmount.
  useEffect(() => {
    return () => {
      if (lingerTimerRef.current !== null) {
        window.clearTimeout(lingerTimerRef.current);
        lingerTimerRef.current = null;
      }
    };
  }, []);

  const scheduleLinger = useCallback(() => {
    if (lingerTimerRef.current !== null) {
      window.clearTimeout(lingerTimerRef.current);
    }
    lingerTimerRef.current = window.setTimeout(() => {
      lingerTimerRef.current = null;
      setLastResult(null);
      setErrorMessage(null);
    }, RESULT_LINGER_MS);
  }, []);

  const request = useCallback(
    (row: number, column: number) => {
      if (pending || exhausted) return;
      const seq = ++requestSeqRef.current;
      setPending(true);
      setErrorMessage(null);
      void solver
        .requestHint(puzzleId, row, column)
        .then((result) => {
          if (seq !== requestSeqRef.current) return;
          setHintsRemaining(result.hintsRemaining);
          setLastResult({
            row: result.row,
            column: result.column,
            letter: result.letter,
          });
          if (result.hintsRemaining <= 0) setExhausted(true);
          onRevealRef.current?.(result.row, result.column, result.letter);
          scheduleLinger();
        })
        .catch((err: unknown) => {
          if (seq !== requestSeqRef.current) return;
          if (err instanceof HintRequestError) {
            if (err.kind === 'budget-exhausted') {
              setExhausted(true);
              setHintsRemaining(0);
              setErrorMessage('Indices épuisés');
            } else if (err.kind === 'invalid-coord') {
              // Stale-focus race; silent no-op for the user, the linger
              // tick still fires so an in-flight pill clears.
              scheduleLinger();
              return;
            } else {
              setErrorMessage('Erreur, réessayez');
            }
          } else {
            setErrorMessage('Erreur, réessayez');
          }
          scheduleLinger();
        })
        .finally(() => {
          if (seq !== requestSeqRef.current) return;
          setPending(false);
        });
    },
    [exhausted, pending, puzzleId, scheduleLinger, solver],
  );

  return {
    hintsRemaining,
    exhausted,
    pending,
    lastResult,
    errorMessage,
    request,
  };
}
