import { css } from 'styled-system/css';
import type { ArrowDirection } from '@/domain';
import type { Clue } from './useGridNavigation';

// Sticky so the active clue stays visible when Android's soft keyboard scrolls the panel off the top of the viewport.
const panel = css({
  position: 'sticky',
  top: 0,
  zIndex: 10,
  width: '100%',
  maxWidth: '480px',
  margin: '0 auto',
  padding: 'sm',
  border: '1px solid token(colors.border)',
  borderRadius: 'sm',
  // Constant shadow — avoids IntersectionObserver wiring; visible against the cream bg once the panel sticks.
  boxShadow: '0 2px 6px -2px rgba(0, 0, 0, 0.08)',
  bg: 'definition',
  color: 'fg',
  textAlign: 'left',
  fontFamily: 'body',
  fontSize: 'body',
  lineHeight: '1.3',
  display: 'flex',
  alignItems: 'center',
  gap: 'sm',
  // 2.5rem — tighter than 3rem so the empty placeholder doesn't dominate the top of a small phone screen.
  minHeight: '2.5rem',
});
const arrow = css({
  fontSize: 'lg',
  color: 'accent',
  flexShrink: 0,
  lineHeight: 1,
});
const text = css({
  flex: 1,
  fontWeight: 'bold',
  color: 'leaf.700',
  overflowWrap: 'break-word',
  wordBreak: 'normal',
});
const placeholder = css({
  flex: 1,
  fontStyle: 'italic',
  color: 'accent',
});

const arrowGlyph: Record<ArrowDirection, string> = {
  right: '→',
  down: '↓',
  'down-right': '↳',
  'right-down': '↴',
};
const arrowLabel: Record<ArrowDirection, string> = {
  right: 'horizontale',
  down: 'verticale',
  'down-right': 'horizontale',
  'right-down': 'verticale',
};

export function CurrentCluePanel({ clue }: { clue: Clue | null }) {
  if (!clue) {
    return (
      <div className={panel} role="status" aria-live="polite" data-testid="current-clue-panel">
        <span className={placeholder}>
          Sélectionnez une case pour afficher la définition.
        </span>
      </div>
    );
  }
  return (
    <div className={panel} role="status" aria-live="polite" data-testid="current-clue-panel">
      <span className={arrow} aria-label={`définition ${arrowLabel[clue.clue.arrow]}`}>
        {arrowGlyph[clue.clue.arrow]}
      </span>
      <span className={text}>{clue.clue.text}</span>
    </div>
  );
}
