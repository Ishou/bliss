import { useCallback, useEffect, useRef, useState } from 'react';
import {
  HintRequestError,
  type PuzzleSolver,
} from '@/application';

// Owner-side state for the hint affordance. Spends one credit per
// `request(word)`; the server is authoritative on the running counter,
// so a 200 always overwrites `hintsRemaining`. A 429 (`budget-exhausted`)
// flips `exhausted` and disables the affordance for the rest of this
// puzzle. Other 4xx and transient errors surface as `errorMessage`
// without changing the budget. Reset when the puzzle reference changes.

const RESULT_LINGER_MS = 4_000;

export interface HintLastResult {
  readonly word: string;
  readonly exists: boolean;
}

export interface HintRequestState {
  readonly hintsRemaining: number;
  readonly exhausted: boolean;
  readonly pending: boolean;
  readonly lastResult: HintLastResult | null;
  readonly errorMessage: string | null;
  readonly request: (word: string) => void;
}

export function useHintRequest(
  puzzleId: string,
  hintsAllowed: number,
  solver: PuzzleSolver,
): HintRequestState {
  const [hintsRemaining, setHintsRemaining] = useState<number>(hintsAllowed);
  const [exhausted, setExhausted] = useState<boolean>(false);
  const [pending, setPending] = useState<boolean>(false);
  const [lastResult, setLastResult] = useState<HintLastResult | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const lingerTimerRef = useRef<number | null>(null);
  const requestSeqRef = useRef(0);

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
    (word: string) => {
      if (pending || exhausted) return;
      const trimmed = word.trim();
      if (trimmed.length < 2) return;
      const seq = ++requestSeqRef.current;
      setPending(true);
      setErrorMessage(null);
      void solver
        .requestHint(puzzleId, trimmed)
        .then((result) => {
          if (seq !== requestSeqRef.current) return;
          setHintsRemaining(result.hintsRemaining);
          setLastResult({ word: result.word, exists: result.exists });
          if (result.hintsRemaining <= 0) setExhausted(true);
          scheduleLinger();
        })
        .catch((err: unknown) => {
          if (seq !== requestSeqRef.current) return;
          if (err instanceof HintRequestError) {
            if (err.kind === 'budget-exhausted') {
              setExhausted(true);
              setHintsRemaining(0);
              setErrorMessage('Indices épuisés');
            } else if (err.kind === 'invalid-word') {
              setErrorMessage('Mot invalide');
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
