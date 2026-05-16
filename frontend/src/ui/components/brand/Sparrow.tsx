import { css, cx } from 'styled-system/css';

// WordSparrow brand mark — ADR-0005 §6.
//
// Sage silhouette with a single dark eye; the eye is filled with whatever
// surface the sparrow sits on so the negative space stays clean against
// any of the palette's surface roles. The default `eye` is `bg` (the page
// background); pass `surface` (or any other token) when nesting the icon
// inside a tile / card / pill.

export type SparrowEyeSurface = 'bg' | 'surface' | 'surfaceElevated';

const baseStyles = css({
  // Block so callers can use vertical-align cleanly inside the lockup.
  display: 'inline-block',
  flexShrink: 0,
  // Body fill rides the brand sage role; a future light-theme swap
  // re-resolves through the token graph.
  color: 'accent',
  // The eye fill is read off `currentBackground` via inline style on the
  // <circle> below; nothing here drives it.
});

const eyeFillByToken: Record<SparrowEyeSurface, string> = {
  bg: 'token(colors.bg)',
  surface: 'token(colors.surface)',
  surfaceElevated: 'token(colors.surfaceElevated)',
};

export interface SparrowProps {
  readonly width?: number;
  readonly eye?: SparrowEyeSurface;
  readonly className?: string;
}

export function Sparrow({ width = 24, eye = 'bg', className }: SparrowProps) {
  // Aspect ratio comes from the brief's 36×24 viewBox.
  const height = (width * 24) / 36;
  return (
    <svg
      viewBox="0 0 36 24"
      width={width}
      height={height}
      className={cx(baseStyles, className)}
      aria-hidden="true"
      focusable="false"
    >
      <path d="M 1 11 L 9 12 L 7 17 L 4 15 Z" fill="currentColor" />
      <ellipse cx="15" cy="13" rx="9" ry="6" fill="currentColor" />
      <circle cx="23" cy="9" r="5" fill="currentColor" />
      <path d="M 27 8 L 33 9 L 27 10 Z" fill="currentColor" />
      <circle cx="24" cy="8" r="0.95" fill={eyeFillByToken[eye]} />
    </svg>
  );
}
