import { css } from 'styled-system/css';
import type { ArrowDirection } from '@/domain';
import type { Clue } from './useGridNavigation';

// Sticky-pinned to the top of the page-level scroll. The panel is rendered
// as a direct child of `<main>` (Grid returns a fragment, not a wrapping
// div) so its containing block is the page itself — taller than the
// viewport, so sticky has room to stick. Width matches the grid below
// (100% / 480px-cap / centered) so the two align horizontally on every
// breakpoint, instead of the panel spanning a wider area than the grid.
//
// Mobile (`base`): full available width inside `<main>`'s padding, slightly
// larger font for thumb-distance reading.
// Desktop (`md`): capped at 480px (same as grid), font reverts to body.
const panel = css({
  position: 'sticky',
  top: 0,
  zIndex: 10,
  width: '100%',
  maxWidth: '480px',
  margin: '0 auto',
  paddingBlock: 'sm',
  paddingInline: 'sm',
  border: '1px solid token(colors.border)',
  borderRadius: 'sm',
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
