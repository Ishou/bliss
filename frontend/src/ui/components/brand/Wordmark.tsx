import { css, cx } from 'styled-system/css';

// WordSparrow wordmark — ADR-0043 §4 (supersedes ADR-0005 §6).
//
// Bicolor + bi-style: `Word` roman in `fg` (forêt profonde), `Sparrow`
// italic Fraunces in `accent` (mousse) with `font-variation-settings:
// 'opsz' 144` so the variable-font display-optical axis renders at its
// largest, most editorial cut. Weight 500 throughout (spec forbids
// 600/700). Sizes follow ADR-0005 §6 display sizes: 22px hero, 16px
// desktop header, 13px mobile header — unchanged by ADR-0043.

export type WordmarkSize = 'hero' | 'desktop' | 'mobile';

const SIZE_PX: Record<WordmarkSize, number> = {
  hero: 22,
  desktop: 16,
  mobile: 13,
};

const wordmarkStyles = css({
  fontFamily: 'heading',
  fontWeight: 'medium',
  letterSpacing: '-0.015em',
  lineHeight: '1',
  margin: 0,
  whiteSpace: 'nowrap',
  // Default to primary text colour; the second span re-paints itself moss.
  color: 'fg',
});

// "Sparrow" half: italic Fraunces in moss with opsz cranked to 144 so the
// variable-font display axis renders at its most editorial cut. The
// `font-variation-settings` rule cascades from this span only; "Word"
// stays roman.
const sparrowSpanStyles = css({
  color: 'accent',
  fontStyle: 'italic',
  fontVariationSettings: "'opsz' 144",
});

export interface WordmarkProps {
  readonly size?: WordmarkSize;
  readonly className?: string;
}

export function Wordmark({ size = 'desktop', className }: WordmarkProps) {
  return (
    <span
      lang="en"
      className={cx(wordmarkStyles, className)}
      style={{ fontSize: `${SIZE_PX[size]}px` }}
    >
      Word<span className={sparrowSpanStyles} data-testid="wordmark-sage">Sparrow</span>
    </span>
  );
}
