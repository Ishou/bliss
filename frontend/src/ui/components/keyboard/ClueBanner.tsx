import { useCallback, type MouseEvent } from 'react';
import { css } from 'styled-system/css';
import { ArrowIcon, arrowLabel } from '@/ui/components/grid/ClueArrowIcon';
import { LetterPreview } from '@/ui/components/grid/LetterPreview';
import type { Clue } from '@/ui/components/grid/useGridNavigation';

const banner = css({
  display: 'flex',
  flexDirection: 'column',
  gap: '6px',
  paddingInline: '10px',
  paddingBlock: '8px',
  minHeight: '44px',
  bg: 'surfaceElevated',
  borderRadius: '10px',
  border: '1px solid token(colors.border)',
});

const block = css({
  display: 'flex',
  alignItems: 'center',
  gap: '8px',
  minWidth: 0,
});

const altBlockTappable = css({
  cursor: 'pointer',
  touchAction: 'manipulation',
  background: 'none',
  border: 'none',
  padding: '0',
  font: 'inherit',
  color: 'inherit',
  textAlign: 'left',
  width: '100%',
  _active: { transform: 'scale(0.99)' },
});

const arrowGlyph = css({
  flexShrink: 0,
  width: '20px',
  height: '20px',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  color: 'secondary.400',
  '& svg': { width: '14px', height: '14px' },
});

const arrowGlyphMuted = css({ color: 'fgMuted' });

const clueText = css({
  flex: 1,
  minWidth: 0,
  fontSize: '13px',
  color: 'fg',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
});

const clueTextMuted = css({ color: 'fgMuted' });

const empty = css({
  flex: 1,
  fontSize: '13px',
  color: 'fgMuted',
  fontStyle: 'italic',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
});

export interface ClueBannerProps {
  readonly clue: Clue | null;
  readonly alternateClue: Clue | null;
  readonly onToggleDirection: () => void;
  readonly getEntryAt: (row: number, col: number) => string;
  readonly focusedPosition: { row: number; col: number } | null;
}

export function ClueBanner({
  clue,
  alternateClue,
  onToggleDirection,
  getEntryAt,
  focusedPosition,
}: ClueBannerProps) {
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
      <div className={block}>
        <span
          className={arrowGlyph}
          role="img"
          aria-label={`définition ${arrowLabel[clue.clue.arrow]}`}
        >
          <ArrowIcon arrow={clue.clue.arrow} />
        </span>
        <span className={clueText} title={clue.clue.text}>
          {clue.clue.text}
        </span>
        <LetterPreview
          cells={clue.cells}
          focusedPosition={focusedPosition}
          getEntryAt={getEntryAt}
        />
      </div>
      {alternateClue ? (
        <button
          type="button"
          className={`${block} ${altBlockTappable}`}
          aria-label={`Basculer sur la définition ${arrowLabel[alternateClue.clue.arrow]}`}
          onMouseDown={handleAltMouseDown}
          onClick={onToggleDirection}
        >
          <span
            className={`${arrowGlyph} ${arrowGlyphMuted}`}
            role="img"
            aria-label={`alternative : définition ${arrowLabel[alternateClue.clue.arrow]}`}
          >
            <ArrowIcon arrow={alternateClue.clue.arrow} />
          </span>
          <span className={`${clueText} ${clueTextMuted}`} title={alternateClue.clue.text}>
            {alternateClue.clue.text}
          </span>
          <LetterPreview
            cells={alternateClue.cells}
            focusedPosition={focusedPosition}
            getEntryAt={getEntryAt}
            muted
          />
        </button>
      ) : null}
    </div>
  );
}
