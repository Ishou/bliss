import { useEffect, useRef } from 'react';
import { css } from 'styled-system/css';
import type { Puzzle } from '@/domain';
import type { FocusedCell } from '@/ui/components/grid/HintControl';
import type { Clue } from '@/ui/components/grid/useGridNavigation';
import { ActionRow } from './ActionRow';
import { AZERTY_ROWS } from './azertyLayout';
import { ClueBanner } from './ClueBanner';
import { KeyboardKey } from './KeyboardKey';

const panel = css({
  position: 'fixed',
  insetInline: 0,
  insetBlockEnd: 0,
  bg: 'surface',
  borderTop: '1px solid token(colors.border)',
  paddingInline: '6px',
  paddingBlockStart: '8px',
  paddingBlockEnd: 'calc(env(safe-area-inset-bottom) + 8px)',
  display: 'flex',
  flexDirection: 'column',
  gap: '6px',
  zIndex: 20,
});

const row = css({
  display: 'flex',
  gap: '4px',
  justifyContent: 'center',
});

const hintLabel = css({
  display: 'inline-flex',
  alignItems: 'center',
  gap: '4px',
  fontSize: '12px',
  fontWeight: 'medium',
});

export interface MobileKeyboardProps {
  readonly onLetter: (char: string) => void;
  readonly onBackspace: () => void;
  readonly onToggleDirection: () => void;
  readonly onPrevClue: () => void;
  readonly onNextClue: () => void;
  readonly onRequestHint: () => void;
  readonly activeClue: Clue | null;
  readonly alternateClue: Clue | null;
  readonly hintRemaining: number;
  readonly hintAllowed: number;
  readonly hintExhausted: boolean;
  readonly hintPending: boolean;
  // Imperative read at click time (ADR-0002 §4) — mirrors HintControl.getFocusedCell.
  readonly getFocusedCell: () => FocusedCell | null;
  // Reads cell entries for the banner letter-preview row; identity bumps per write per ADR-0002 §4.
  readonly getEntryAt: (row: number, col: number) => string;
  // The local user's focused cell — drives the rose underline on the active-clue letter preview.
  readonly focusedPosition: { row: number; col: number } | null;
  // Puzzle + live transform state feed the in-panel KeyboardMinimap (action-row slot).
  readonly puzzle: Puzzle;
  readonly scale: number;
  readonly positionX: number;
  readonly positionY: number;
  readonly contentWidth: number;
  readonly contentHeight: number;
}

export function MobileKeyboard(props: MobileKeyboardProps) {
  const {
    onLetter,
    onBackspace,
    onToggleDirection,
    onPrevClue,
    onNextClue,
    onRequestHint,
    activeClue,
    alternateClue,
    hintRemaining,
    hintAllowed,
    hintExhausted,
    hintPending,
    getFocusedCell,
    getEntryAt,
    focusedPosition,
    puzzle,
    scale,
    positionX,
    positionY,
    contentWidth,
    contentHeight,
  } = props;

  const panelRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = panelRef.current;
    if (!el) return;
    // Publish initial height before ResizeObserver fires so consumers reserve space correctly.
    const publish = () => {
      const h = Math.ceil(el.getBoundingClientRect().height);
      document.documentElement.style.setProperty('--mobile-kb-height', `${h}px`);
    };
    publish();
    let ro: ResizeObserver | null = null;
    if (typeof ResizeObserver !== 'undefined') {
      ro = new ResizeObserver(publish);
      ro.observe(el);
    }
    return () => {
      ro?.disconnect();
      document.documentElement.style.removeProperty('--mobile-kb-height');
    };
  }, []);

  // Click-time guard preserves uncontrolled-input contract (ADR-0002 §4).
  const handleRequestHint = () => {
    const cell = getFocusedCell();
    if (!cell || cell.isLocked) return;
    onRequestHint();
  };

  const hintDisabled = hintExhausted || hintPending || activeClue === null;
  const lettersInert = activeClue === null;

  return (
    <div ref={panelRef} className={panel} role="group" aria-label="Clavier mots fléchés">
      <ClueBanner
        clue={activeClue}
        alternateClue={alternateClue}
        onToggleDirection={onToggleDirection}
        getEntryAt={getEntryAt}
        focusedPosition={focusedPosition}
      />
      <ActionRow
        onPrev={onPrevClue}
        onNext={onNextClue}
        puzzle={puzzle}
        scale={scale}
        positionX={positionX}
        positionY={positionY}
        contentWidth={contentWidth}
        contentHeight={contentHeight}
      />
      {AZERTY_ROWS.map((letters, rowIdx) => (
        <div key={rowIdx} className={row}>
          {letters.map((ch) => (
            <KeyboardKey
              key={ch}
              label={ch}
              ariaLabel={`Lettre ${ch}`}
              disabled={lettersInert}
              onPress={() => onLetter(ch)}
            />
          ))}
          {rowIdx === AZERTY_ROWS.length - 1 ? (
            <>
              <KeyboardKey
                label={
                  <span className={hintLabel}>
                    💡 Indice {hintRemaining} / {hintAllowed}
                  </span>
                }
                ariaLabel={`Demander un indice, ${hintRemaining} restants`}
                variant="action"
                disabled={hintDisabled}
                onPress={handleRequestHint}
              />
              <KeyboardKey
                label="⌫"
                ariaLabel="Effacer"
                variant="action"
                onPress={onBackspace}
              />
            </>
          ) : null}
        </div>
      ))}
    </div>
  );
}
