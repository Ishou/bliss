import { css, cx } from 'styled-system/css';
import { Sparrow, type SparrowEyeSurface } from './Sparrow';
import { Wordmark, type WordmarkSize } from './Wordmark';

// Brand lockup: sparrow + 8px gap + wordmark, vertically centered on the
// wordmark's cap height (per ADR-0005 §6). Used in the app header and any
// other surface that needs the full brand mark.

const lockupStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  gap: '8px',
  // Headings inside the lockup must not pick up the page's `text-align`.
  textAlign: 'left',
});

// The bird scales with the wordmark size so the lockup stays balanced
// across the three display sizes.
const SPARROW_WIDTH_PX: Record<WordmarkSize, number> = {
  hero: 32,
  desktop: 26,
  mobile: 22,
};

export interface LockupProps {
  readonly size?: WordmarkSize;
  readonly eye?: SparrowEyeSurface;
  readonly className?: string;
}

export function Lockup({ size = 'desktop', eye = 'bg', className }: LockupProps) {
  return (
    <span className={cx(lockupStyles, className)} aria-label="WordSparrow">
      <Sparrow width={SPARROW_WIDTH_PX[size]} eye={eye} />
      <Wordmark size={size} />
    </span>
  );
}
