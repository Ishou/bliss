import { css } from 'styled-system/css';
import type { ArrowDirection } from '@/domain';
import type { Clue } from './useGridNavigation';

// Fixed-positioned, anchored to the visible viewport top. `position: sticky`
// was tried first but the panel sits inside Grid's flex column, whose height
// equals its content — sticky needs a containing block taller than the
// scroll position to actually pin, and unsticks immediately when content
// fits in the viewport. `position: fixed` works regardless of the layout
// context. The trade-off is that the panel no longer takes flow space, so
// `routes/index.tsx`'s `pageStyles` carries an explicit `paddingTop` to
// reserve room for it at the top of the page.
//
// Mobile (`base`): edge-to-edge, status-bar feel — no border-radius, full
// viewport width, single bottom border separating it from the page bg.
// Desktop (`md`): capped at 480px, centered above the grid, full pill border
// with radius — matches the grid container's max-width below.
const panel = css({
  position: 'fixed',
  top: 0,
  left: 0,
  right: 0,
  zIndex: 10,
  maxWidth: { md: '480px' },
  margin: { md: '0 auto' },
  borderRadius: { base: 0, md: 'sm' },
  border: { base: 'none', md: '1px solid token(colors.border)' },
  borderBottom: '1px solid token(colors.border)',
  paddingBlock: 'sm',
  paddingInline: { base: 'md', md: 'sm' },
  // Constant elevation — visible once it sticks against the cream page bg.
  boxShadow: '0 2px 6px -2px rgba(0, 0, 0, 0.08)',
  bg: 'definition',
  color: 'fg',
  textAlign: 'left',
  fontFamily: 'body',
  // `md` (1.125rem) on phones gives better thumb-distance readability than
  // `body` (1rem); desktop reverts to body so the panel doesn't dominate.
  fontSize: { base: 'md', md: 'body' },
  lineHeight: '1.3',
  display: 'flex',
  alignItems: 'center',
  gap: 'sm',
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
