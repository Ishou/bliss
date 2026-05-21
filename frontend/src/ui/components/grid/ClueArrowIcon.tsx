import type { ArrowDirection } from '@/domain';

// Inline-SVG arrow glyphs — same stroke vocabulary as the grid arrows in `Cell.tsx`, scaled for chip size.
export const ARROW_PATHS: Record<ArrowDirection, string> = {
  right: 'M3 12h17 M14 7l6 5-6 5',
  down: 'M12 3v17 M7 14l5 6 5-6',
  'right-down': 'M3 7h14v13 M12 15l5 5 5-5',
  'down-right': 'M7 3v14h13 M15 12l5 5-5 5',
};

// French aria-label mapping for the arrow direction — used by the chip wrappers around `ArrowIcon`.
export const arrowLabel: Record<ArrowDirection, string> = {
  right: 'horizontale',
  down: 'verticale',
  'down-right': 'horizontale',
  'right-down': 'verticale',
};

export function ArrowIcon({ arrow }: { arrow: ArrowDirection }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d={ARROW_PATHS[arrow]} />
    </svg>
  );
}
