// Herbier corner illustrations — ADR-0043 §5.
//
// Sparse botanical SVG line drawings placed in the margins of ContentPage
// routes. The ADR's strict rule is "never on the grid itself, never on
// /grille": ContentPage is the wrapper for content routes (Accueil, Aide,
// legal, Grilles); ViewportPage wraps the grille / lobby playing surfaces.
// So integration at ContentPage gets us the right routes by structure
// alone — no route-aware guard needed.
//
// All variants use currentColor so Panda's `accent` semantic token (mousse,
// `#3f6431`) controls hue. Opacity defaults to 0.4 because dark moss at
// full strength is LOUD on the cream background and these illustrations
// are meant to sit quietly in the periphery.

import * as React from 'react';
import { css, cx } from 'styled-system/css';

export type HerbierCornerPosition =
  | 'top-left'
  | 'top-right'
  | 'bottom-left'
  | 'bottom-right';

export type HerbierVariant = 'lance' | 'oval' | 'twig' | 'single' | 'cluster';

export interface HerbierCornerProps {
  readonly corner: HerbierCornerPosition;
  /** Which botanical variant. Omit to use the default `cluster` motif for every corner. */
  readonly variant?: HerbierVariant;
  /** Visual weight knob. 0.4 keeps the drawing quiet against cream. */
  readonly opacity?: number;
}

const baseStyles = css({
  position: 'absolute',
  // Hidden below `lg`: the 720px content wrapper fills the viewport at
  // ≤1024px, leaving no margin and the doodle overlaps page H1s.
  display: { base: 'none', lg: 'block' },
  width: '52px',
  height: '52px',
  pointerEvents: 'none',
  color: 'accent',
  zIndex: 0,
});

// Generous inset from the page edge so the doodle reads as "in the
// margin" rather than "clipped to the corner". The actual offset uses
// page-level paddings (`lg` ≈ 32 px) instead of the previous `md` ≈ 16 px.
const cornerStyles: Record<HerbierCornerPosition, string> = {
  'top-left': css({ top: 'lg', left: 'lg', transform: 'rotate(-15deg)' }),
  'top-right': css({ top: 'lg', right: 'lg', transform: 'rotate(110deg)' }),
  'bottom-left': css({ bottom: 'lg', left: 'lg', transform: 'rotate(-110deg)' }),
  'bottom-right': css({ bottom: 'lg', right: 'lg', transform: 'rotate(150deg)' }),
};

// Default variant per corner. All four corners now share the small
// `cluster` variant — three little leaves at varied angles — and only
// the rotation differs (see cornerStyles). The previous mix of
// lance/oval/twig at 80 px read as four disparate plants squatting in
// the corners; uniform clusters with per-corner rotation read as a
// single decorative motif applied four times. The `lance`/`oval`/
// `twig`/`single` variants stay available for explicit-prop callers.
const variantByCorner: Record<HerbierCornerPosition, HerbierVariant> = {
  'top-left': 'cluster',
  'top-right': 'cluster',
  'bottom-left': 'cluster',
  'bottom-right': 'cluster',
};

// SVG path data is verbatim from the wordsparrow-textures mockup (ADR-0043
// §5 reference). Literal `#5a8a4a` replaced by `currentColor` so the
// semantic accent token controls colour.
const VARIANTS: Record<HerbierVariant, React.ReactElement> = {
  lance: (
    <svg viewBox="0 0 32 64" xmlns="http://www.w3.org/2000/svg">
      <path
        d="M 16 4 C 8 10, 5 24, 8 38 C 11 50, 14 56, 16 58 C 18 56, 21 50, 24 38 C 27 24, 24 10, 16 4 Z"
        fill="none"
        stroke="currentColor"
        strokeWidth="0.7"
        strokeLinecap="round"
      />
      <path d="M 16 6 L 16 56" stroke="currentColor" strokeWidth="0.5" />
      <path
        d="M 16 14 L 11 17 M 16 14 L 21 17 M 16 22 L 10 26 M 16 22 L 22 26 M 16 30 L 11 33 M 16 30 L 21 33 M 16 38 L 12 40 M 16 38 L 20 40 M 16 46 L 13 48 M 16 46 L 19 48"
        stroke="currentColor"
        strokeWidth="0.35"
        fill="none"
      />
    </svg>
  ),
  oval: (
    <svg viewBox="0 0 36 60" xmlns="http://www.w3.org/2000/svg">
      <path
        d="M 18 3 C 8 8, 4 22, 6 36 C 9 48, 14 54, 18 56 C 22 54, 27 48, 30 36 C 32 22, 28 8, 18 3 Z"
        fill="none"
        stroke="currentColor"
        strokeWidth="0.7"
      />
      <path d="M 18 5 L 18 54" stroke="currentColor" strokeWidth="0.5" />
      <path
        d="M 18 14 L 12 18 M 18 14 L 24 18 M 18 24 L 10 28 M 18 24 L 26 28 M 18 34 L 11 38 M 18 34 L 25 38 M 18 44 L 14 46 M 18 44 L 22 46"
        stroke="currentColor"
        strokeWidth="0.35"
        fill="none"
      />
    </svg>
  ),
  twig: (
    <svg viewBox="0 0 100 80" xmlns="http://www.w3.org/2000/svg">
      <path
        d="M 0 60 Q 30 50 60 30 Q 75 20 95 12"
        stroke="currentColor"
        strokeWidth="0.9"
        fill="none"
        strokeLinecap="round"
      />
      <ellipse cx="20" cy="56" rx="3" ry="6" fill="currentColor" transform="rotate(-50 20 56)" />
      <ellipse cx="38" cy="48" rx="3" ry="6" fill="currentColor" transform="rotate(-30 38 48)" />
      <ellipse cx="56" cy="36" rx="3" ry="6" fill="currentColor" transform="rotate(-10 56 36)" />
      <ellipse cx="72" cy="22" rx="3" ry="6" fill="currentColor" transform="rotate(10 72 22)" />
      <ellipse cx="88" cy="14" rx="3" ry="6" fill="currentColor" transform="rotate(30 88 14)" />
      <ellipse
        cx="14"
        cy="50"
        rx="2.5"
        ry="5"
        fill="none"
        stroke="currentColor"
        strokeWidth="0.5"
        transform="rotate(50 14 50)"
      />
      <ellipse
        cx="32"
        cy="42"
        rx="2.5"
        ry="5"
        fill="none"
        stroke="currentColor"
        strokeWidth="0.5"
        transform="rotate(70 32 42)"
      />
      <ellipse
        cx="50"
        cy="30"
        rx="2.5"
        ry="5"
        fill="none"
        stroke="currentColor"
        strokeWidth="0.5"
        transform="rotate(90 50 30)"
      />
    </svg>
  ),
  single: (
    <svg viewBox="0 0 28 52" xmlns="http://www.w3.org/2000/svg">
      <path
        d="M 14 4 C 7 9, 4 20, 6 32 C 9 42, 12 46, 14 48 C 16 46, 19 42, 22 32 C 24 20, 21 9, 14 4 Z"
        fill="currentColor"
        opacity="0.85"
      />
    </svg>
  ),
  cluster: (
    <svg viewBox="0 0 32 28" xmlns="http://www.w3.org/2000/svg">
      <ellipse cx="8" cy="12" rx="3" ry="6" fill="currentColor" transform="rotate(-20 8 12)" />
      <ellipse cx="16" cy="6" rx="3" ry="6" fill="currentColor" transform="rotate(15 16 6)" />
      <ellipse
        cx="22"
        cy="18"
        rx="3"
        ry="6"
        fill="none"
        stroke="currentColor"
        strokeWidth="0.6"
        transform="rotate(40 22 18)"
      />
    </svg>
  ),
};

export function HerbierCorner({ corner, variant, opacity = 0.4 }: HerbierCornerProps) {
  const resolved = variant ?? variantByCorner[corner];
  return (
    <div
      className={cx(baseStyles, cornerStyles[corner])}
      aria-hidden="true"
      style={{ opacity }}
      data-testid={`herbier-corner-${corner}`}
      data-variant={resolved}
    >
      {VARIANTS[resolved]}
    </div>
  );
}
