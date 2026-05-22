import { render } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { ClueBanner } from '@/ui/components/keyboard/ClueBanner';
import type { Clue } from '@/ui/components/grid/useGridNavigation';

// jsdom skips Panda stylesheet evaluation; measuredBannerHeight checks for the h_NNpx atomic class instead.
const ARROW_GLYPH_HEIGHT = 20;
const LETTER_PREVIEW_HEIGHT = 24;

function rowIntrinsicHeight(row: Element): number {
  const hasLetterPreview = row.querySelector('span[aria-hidden]') !== null;
  return hasLetterPreview
    ? Math.max(ARROW_GLYPH_HEIGHT, LETTER_PREVIEW_HEIGHT)
    : ARROW_GLYPH_HEIGHT;
}

function measuredBannerHeight(banner: HTMLElement): number {
  const fixed = banner.className.match(/(?:^| )h_(\d+)px(?: |$)/);
  if (fixed) return Number.parseInt(fixed[1], 10);
  return Array.from(banner.children).reduce((sum, child) => sum + rowIntrinsicHeight(child), 0);
}

const makeClue = (
  text: string,
  len: number,
  dir: 'across' | 'down',
  row = 0,
  col0 = 0,
): Clue => ({
  definition: {
    kind: 'definition',
    position: { row, col: col0 },
    clues: [{ text, arrow: dir === 'across' ? 'right' : 'down' }],
  },
  clue: { text, arrow: dir === 'across' ? 'right' : 'down' },
  direction: dir,
  cells: Array.from({ length: len }, (_, i) => ({
    kind: 'letter',
    position: dir === 'across' ? { row, col: col0 + 1 + i } : { row: row + 1 + i, col: col0 },
    entry: '',
  })),
});

const noEntries = () => '';

describe('ClueBanner height parity', () => {
  it('banner total height is identical across empty / single-clue / intersection states', () => {
    const empty = render(
      <ClueBanner
        clue={null}
        alternateClue={null}
        onToggleDirection={() => undefined}
        getEntryAt={noEntries}
        focusedPosition={null}
      />,
    );
    const single = render(
      <ClueBanner
        clue={makeClue('Fruit jaune', 6, 'across')}
        alternateClue={null}
        onToggleDirection={() => undefined}
        getEntryAt={noEntries}
        focusedPosition={null}
      />,
    );
    const intersection = render(
      <ClueBanner
        clue={makeClue('Fruit jaune', 6, 'across')}
        alternateClue={makeClue('Vert', 4, 'down')}
        onToggleDirection={() => undefined}
        getEntryAt={noEntries}
        focusedPosition={null}
      />,
    );

    const hEmpty = measuredBannerHeight(empty.container.firstElementChild as HTMLElement);
    const hSingle = measuredBannerHeight(single.container.firstElementChild as HTMLElement);
    const hIntersection = measuredBannerHeight(
      intersection.container.firstElementChild as HTMLElement,
    );

    expect(hEmpty).toBe(hSingle);
    expect(hSingle).toBe(hIntersection);
  });

  it('outer banner declares a fixed `height` (not just `minHeight`) so children cannot drive resize', () => {
    const { container } = render(
      <ClueBanner
        clue={null}
        alternateClue={null}
        onToggleDirection={() => undefined}
        getEntryAt={noEntries}
        focusedPosition={null}
      />,
    );
    const banner = container.firstElementChild as HTMLElement;
    expect(banner.className).toMatch(/(?:^| )h_\d+px(?: |$)/);
  });
});
