import { css } from 'styled-system/css';
import type { ArrowDirection } from '@/domain';
import type { Clue } from './useGridNavigation';

// Out-of-grid clue panel. Cell-internal text is intentionally tiny so the
// full clue rarely fits without zoom; this panel guarantees the active
// clue is always readable at body font size, regardless of grid density.
//
// Sticky-pinned to the top of the viewport so the active clue stays
// visible on mobile when the soft keyboard pushes content up — without
// it the panel scrolls off the top of the visible area and the player
// has to dismiss the keyboard to re-read the prompt. Subtle bottom
// shadow gives a visual separation from the grid below once it sticks
// (the shadow is constant rather than scroll-state-driven so we don't
// need IntersectionObserver wiring).
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
  // Soft elevation — kept subtle so it doesn't compete with the grid's
  // own border. Becomes visible against the cream page bg when the
  // panel sticks to the top of the viewport on scroll.
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
  // Tighter than 3rem — gives the empty placeholder enough room without
  // dominating the top of a small phone screen.
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
