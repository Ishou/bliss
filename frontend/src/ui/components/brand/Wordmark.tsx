import { css, cx } from 'styled-system/css';

// WordSparrow wordmark — ADR-0005 §6 brand brief.
//
// Always one word, bicolor: `Word` in primary fg, `Sparrow` in sage. One
// weight only (500); spec forbids 600/700. Letter-spacing slightly tight.
// Sizes follow the brief's display sizes: 22px hero, 16px desktop header,
// 13px mobile header.

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
  // Default to primary text colour; the second span re-paints itself sage.
  color: 'fg',
});

const sageSpanStyles = css({ color: 'accent' });

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
      Word<span className={sageSpanStyles} data-testid="wordmark-sage">Sparrow</span>
    </span>
  );
}
