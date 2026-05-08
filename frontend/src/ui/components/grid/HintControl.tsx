import { useCallback } from 'react';
import { css, cx } from 'styled-system/css';
import { IconButton } from '@/ui/components/primitives';
import { HintIcon } from '@/ui/components/icons';
import type { HintLastResult } from './useHintRequest';

// Toolbar affordance for the per-puzzle hint budget. Clicking spends one
// credit checking whether the active word exists in the corpus; the
// status pill (aria-live) reports `✓ existe` / `✗ introuvable` /
// `Indices épuisés`. Exhausted = budget hit zero (server 429 or 200
// with `hintsRemaining: 0`); the button locks for the rest of the
// puzzle.

const containerStyles = css({
  position: 'relative',
  display: 'inline-flex',
  alignItems: 'center',
  gap: '6px',
});

const badgeStyles = css({
  fontFamily: 'mono',
  fontSize: 'xs',
  color: 'fgMuted',
  fontVariantNumeric: 'tabular-nums',
  letterSpacing: '0.02em',
  // Reserve space for the two-character ratio (e.g. "0/3") so the
  // toolbar layout doesn't reflow as the count drops.
  minWidth: '2.4em',
  textAlign: 'left',
});

const statusBaseStyles = css({
  position: 'absolute',
  // Sit just below the toolbar; the parent panel positions relatively.
  top: 'calc(100% + 4px)',
  right: 0,
  fontFamily: 'body',
  fontSize: 'xs',
  paddingX: '8px',
  paddingY: '4px',
  borderRadius: 'sm',
  whiteSpace: 'nowrap',
  pointerEvents: 'none',
});

const statusSuccessStyles = css({
  bg: 'successBg',
  color: 'successText',
});

const statusFailureStyles = css({
  bg: 'surface',
  color: 'fgMuted',
  border: '1px solid token(colors.border)',
});

const statusErrorStyles = css({
  bg: 'errorBg',
  color: 'errorText',
});

const liveRegionStyles = css({
  position: 'absolute',
  width: '1px',
  height: '1px',
  padding: 0,
  margin: '-1px',
  overflow: 'hidden',
  clip: 'rect(0,0,0,0)',
  whiteSpace: 'nowrap',
  border: 0,
});

export interface HintControlProps {
  readonly hintsRemaining: number;
  readonly hintsAllowed: number;
  readonly exhausted: boolean;
  readonly pending: boolean;
  readonly lastResult: HintLastResult | null;
  readonly errorMessage: string | null;
  /** Returns the current word the user is filling, or `null` if none. */
  readonly getCurrentWord: () => string | null;
  readonly onRequest: (word: string) => void;
}

export function HintControl({
  hintsRemaining,
  hintsAllowed,
  exhausted,
  pending,
  lastResult,
  errorMessage,
  getCurrentWord,
  onRequest,
}: HintControlProps) {
  const handleClick = useCallback(() => {
    const word = getCurrentWord();
    if (!word || word.length < 2) return;
    onRequest(word);
  }, [getCurrentWord, onRequest]);

  const status = renderStatus({ lastResult, errorMessage, exhausted });

  return (
    <div className={containerStyles}>
      <IconButton
        aria-label="Demander un indice"
        tone="accent"
        onClick={handleClick}
        disabled={exhausted || pending}
      >
        <HintIcon />
      </IconButton>
      <span
        className={badgeStyles}
        aria-label={`${hintsRemaining} sur ${hintsAllowed} indices restants`}
      >
        {hintsRemaining}/{hintsAllowed}
      </span>
      {status ? (
        <span
          className={cx(statusBaseStyles, status.className)}
          role="status"
        >
          {status.text}
        </span>
      ) : null}
      <span className={liveRegionStyles} aria-live="polite">
        {status?.text ?? ''}
      </span>
    </div>
  );
}

function renderStatus({
  lastResult,
  errorMessage,
  exhausted,
}: {
  lastResult: HintLastResult | null;
  errorMessage: string | null;
  exhausted: boolean;
}): { text: string; className: string } | null {
  if (errorMessage) {
    return {
      text: errorMessage,
      className: exhausted ? statusErrorStyles : statusFailureStyles,
    };
  }
  if (lastResult) {
    return lastResult.exists
      ? {
          text: `✓ « ${lastResult.word} » existe`,
          className: statusSuccessStyles,
        }
      : {
          text: `✗ « ${lastResult.word} » introuvable`,
          className: statusFailureStyles,
        };
  }
  return null;
}
