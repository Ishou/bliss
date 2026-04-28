import { css } from 'styled-system/css';
import type { ArrowDirection } from '@/domain';
import type { Clue } from './useGridNavigation';

// Out-of-grid clue panel. Cell-internal text is intentionally tiny so the
// full clue rarely fits without zoom; this panel guarantees the active
// clue is always readable at body font size, regardless of grid density.
const panel = css({
  width: '100%',
  maxWidth: '480px',
  margin: '0 auto',
  padding: 'sm',
  border: '1px solid token(colors.border)',
  borderRadius: 'sm',
  bg: 'definition',
  color: 'fg',
  textAlign: 'left',
  fontFamily: 'body',
  fontSize: 'body',
  lineHeight: '1.35',
  display: 'flex',
  alignItems: 'center',
  gap: 'sm',
  minHeight: '3rem',
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
