import { useCallback, type MouseEvent } from 'react';
import { css } from 'styled-system/css';
import { FitText } from '@/ui/components/grid/FitText';
import type { Clue } from '@/ui/components/grid/useGridNavigation';

const banner = css({
  display: 'flex',
  alignItems: 'center',
  gap: '8px',
  paddingInline: '10px',
  paddingBlock: '8px',
  minHeight: '44px',
  bg: 'surfaceElevated',
  borderRadius: '10px',
  border: '1px solid token(colors.border)',
});

const posChip = css({
  flexShrink: 0,
  paddingInline: '8px',
  height: '22px',
  display: 'inline-flex',
  alignItems: 'center',
  borderRadius: '999px',
  bg: 'color-mix(in srgb, token(colors.secondary.400) 18%, transparent)',
  // secondary.700 reaches ~7:1 on the honey-pale surface, clearing WCAG AA 4.5:1 for small text.
  color: 'secondary.700',
  fontSize: '11px',
  fontWeight: 'medium',
});

const clueTextWrap = css({
  flex: 1,
  minWidth: 0,
  fontSize: '13px',
  color: 'fg',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
});

const lenPill = css({
  flexShrink: 0,
  paddingInline: '6px',
  height: '20px',
  display: 'inline-flex',
  alignItems: 'center',
  borderRadius: '4px',
  bg: 'transparent',
  border: '1px solid token(colors.border)',
  color: 'fgMuted',
  fontSize: '11px',
  fontFamily: 'body',
});

const altChip = css({
  flexShrink: 0,
  paddingInline: '8px',
  height: '24px',
  display: 'inline-flex',
  alignItems: 'center',
  gap: '4px',
  borderRadius: '999px',
  bg: 'surface',
  border: '1px solid token(colors.border)',
  color: 'fgMuted',
  fontSize: '11px',
  cursor: 'pointer',
  touchAction: 'manipulation',
  _active: { transform: 'scale(0.97)' },
});

const empty = css({
  flex: 1,
  fontSize: '13px',
  color: 'fgMuted',
  fontStyle: 'italic',
});

const dirLabel = (dir: 'across' | 'down') => (dir === 'across' ? 'horiz.' : 'vert.');

export interface ClueBannerProps {
  readonly clue: Clue | null;
  readonly alternateClue: Clue | null;
  readonly onToggleDirection: () => void;
}

export function ClueBanner({ clue, alternateClue, onToggleDirection }: ClueBannerProps) {
  // Preserve the focused cell on tap: suppress mousedown's implicit focus shift.
  const handleAltMouseDown = useCallback((e: MouseEvent) => e.preventDefault(), []);
  if (!clue) {
    return (
      <div className={banner} aria-live="off">
        <span className={empty}>Touchez une case pour commencer</span>
      </div>
    );
  }
  return (
    <div className={banner}>
      <span className={posChip}>{dirLabel(clue.direction)}</span>
      <span className={clueTextWrap}>
        <FitText text={clue.clue.text} min={0.16} max={0.22} unit="ratio" />
      </span>
      <span className={lenPill}>{clue.cells.length}</span>
      {alternateClue ? (
        <button
          type="button"
          className={altChip}
          aria-label={`Basculer sur la définition ${dirLabel(alternateClue.direction)}`}
          onMouseDown={handleAltMouseDown}
          onClick={onToggleDirection}
        >
          ⇄ {dirLabel(alternateClue.direction)}
        </button>
      ) : null}
    </div>
  );
}
