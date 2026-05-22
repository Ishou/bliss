import { css } from 'styled-system/css';

export type TabDirection = 'previous' | 'next';

export interface TabKeyIconProps {
  readonly direction: TabDirection;
}

// Stroke geometry matches DirectionArrowIcon so line weight reads identically across the nav block.
const STROKE_WIDTH = 2.5;

// Forward tab: shaft + chevron pointing right + vertical stop bar at the right tip.
const TAB_PATH = 'M4 12h13M11 6l6 6-6 6M20 6v12';

// Previous mirrors next via scaleX(-1) — geometry stays identical so the icons read as a pair.
const TRANSFORMS: Record<TabDirection, string> = {
  next: 'scaleX(1)',
  previous: 'scaleX(-1)',
};

const iconStyles = css({
  display: 'inline-flex',
  width: '18px',
  height: '18px',
  '& svg': { width: '100%', height: '100%' },
});

export function TabKeyIcon({ direction }: TabKeyIconProps) {
  return (
    <span className={iconStyles} data-direction={direction}>
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth={STROKE_WIDTH}
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
        focusable={false}
        style={{ transform: TRANSFORMS[direction] }}
      >
        <path d={TAB_PATH} />
      </svg>
    </span>
  );
}
