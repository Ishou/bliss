import { css } from 'styled-system/css';

export type ArrowDirection = 'left' | 'right' | 'up' | 'down';

export interface DirectionArrowIconProps {
  readonly direction: ArrowDirection;
}

// Stroke geometry shared by all four orientations so line weight reads identically.
const STROKE_WIDTH = 2.5;

// Right-arrow base path; other directions derive via CSS rotation to guarantee identical geometry.
const ARROW_PATH = 'M5 12h14M13 6l6 6-6 6';

const ROTATIONS: Record<ArrowDirection, number> = {
  right: 0,
  down: 90,
  left: 180,
  up: 270,
};

const iconStyles = css({
  display: 'inline-flex',
  width: '18px',
  height: '18px',
  '& svg': { width: '100%', height: '100%' },
});

export function DirectionArrowIcon({ direction }: DirectionArrowIconProps) {
  const rotation = ROTATIONS[direction];
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
        style={{ transform: `rotate(${rotation}deg)` }}
      >
        <path d={ARROW_PATH} />
      </svg>
    </span>
  );
}
