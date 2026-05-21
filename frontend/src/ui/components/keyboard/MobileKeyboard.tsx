import { useEffect, useRef } from 'react';
import { css } from 'styled-system/css';
import { AZERTY_ROWS } from './azertyLayout';
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

export interface MobileKeyboardProps {
  readonly onLetter: (char: string) => void;
  readonly onBackspace: () => void;
}

export function MobileKeyboard({ onLetter, onBackspace }: MobileKeyboardProps) {
  const panelRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = panelRef.current;
    if (!el) return;
    // Publish initial height so consumers (grid max-height) reserve space even when ResizeObserver is absent.
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
  return (
    <div ref={panelRef} className={panel} role="group" aria-label="Clavier mots fléchés">
      {AZERTY_ROWS.map((letters, rowIdx) => (
        <div key={rowIdx} className={row}>
          {letters.map((ch) => (
            <KeyboardKey
              key={ch}
              label={ch}
              ariaLabel={`Lettre ${ch}`}
              onPress={() => onLetter(ch)}
            />
          ))}
          {rowIdx === AZERTY_ROWS.length - 1 ? (
            <KeyboardKey
              label="⌫"
              ariaLabel="Effacer"
              variant="action"
              onPress={onBackspace}
            />
          ) : null}
        </div>
      ))}
    </div>
  );
}
