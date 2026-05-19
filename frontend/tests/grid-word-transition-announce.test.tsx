import { act, render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AnnouncerProvider } from '@/ui/components/a11y/Announcer';
import { Grid } from '@/ui/components/grid';
import type { Puzzle } from '@/domain';

// Puzzle fixture copied from frontend/tests/grid-input.test.tsx (TEST_PUZZLE).
// 5×4 grid: across-1 (def at (0,0), cells (0,1)), across-2 (def at (1,0),
// cells (1,1)..(1,4)), down-1 (def at (0,2), cells (1,2)..(3,2)).
// across-2 has 4 cells — satisfies the ≥5-cells guideline adequately for
// format assertion tests; the important property is across + down crossing.
const samplePuzzle: Puzzle = {
  id: 'test',
  title: 'test',
  language: 'fr',
  width: 5,
  height: 4,
  hintsAllowed: 3,
  hintsRemaining: 3,
  cells: [
    {
      kind: 'definition',
      position: { row: 0, col: 0 },
      clues: [{ text: 'across-1', arrow: 'right' }],
    },
    { kind: 'letter', position: { row: 0, col: 1 }, entry: '' },
    {
      kind: 'definition',
      position: { row: 0, col: 2 },
      clues: [{ text: 'down-1', arrow: 'down' }],
    },
    { kind: 'letter', position: { row: 0, col: 3 }, entry: '' },
    { kind: 'letter', position: { row: 0, col: 4 }, entry: '' },
    {
      kind: 'definition',
      position: { row: 1, col: 0 },
      clues: [{ text: 'across-2', arrow: 'right' }],
    },
    { kind: 'letter', position: { row: 1, col: 1 }, entry: '' },
    { kind: 'letter', position: { row: 1, col: 2 }, entry: '' },
    { kind: 'letter', position: { row: 1, col: 3 }, entry: '' },
    { kind: 'letter', position: { row: 1, col: 4 }, entry: '' },
    { kind: 'letter', position: { row: 2, col: 0 }, entry: '' },
    { kind: 'letter', position: { row: 2, col: 1 }, entry: '' },
    { kind: 'letter', position: { row: 2, col: 2 }, entry: '' },
    { kind: 'letter', position: { row: 2, col: 3 }, entry: '' },
    { kind: 'letter', position: { row: 2, col: 4 }, entry: '' },
    { kind: 'letter', position: { row: 3, col: 0 }, entry: '' },
    { kind: 'block', position: { row: 3, col: 1 } },
    { kind: 'letter', position: { row: 3, col: 2 }, entry: '' },
    { kind: 'letter', position: { row: 3, col: 3 }, entry: '' },
    { kind: 'letter', position: { row: 3, col: 4 }, entry: '' },
  ],
};

function inputAt(
  container: HTMLElement,
  row: number,
  col: number,
): HTMLInputElement {
  const el = container.querySelector(
    `input[data-cell-kind="letter"][data-row="${row}"][data-col="${col}"]`,
  );
  if (!el) throw new Error(`no input at (${row},${col})`);
  return el as HTMLInputElement;
}

function politeText(container: HTMLElement): string {
  return container.querySelector('[aria-live="polite"]')?.textContent ?? '';
}

describe('grid — word-transition announcement', () => {
  it('announces clue + slot pattern on entering a new word (across)', () => {
    const { container } = render(
      <AnnouncerProvider>
        <Grid puzzle={samplePuzzle} />
      </AnnouncerProvider>,
    );
    // across-2 starts at (1,1). Focus it — should announce the across-2 clue.
    const acrossCell = inputAt(container, 1, 1);
    act(() => {
      acrossCell.focus();
    });
    const text = politeText(container);
    // Must contain the direction label
    expect(text).toMatch(/mot horizontal de \d+ lettres/);
    // Must end with a slot pattern: each token is either a letter or 'point'
    expect(text).toMatch(/: (point|[A-ZÉÈÀÇÊÎÔÛŸ])(, (point|[A-ZÉÈÀÇÊÎÔÛŸ]))*$/);
    // Must open with the clue text in guillemets
    expect(text).toMatch(/^« .+? »/);
  });

  it('announces a down clue with "mot vertical"', () => {
    const { container } = render(
      <AnnouncerProvider>
        <Grid puzzle={samplePuzzle} />
      </AnnouncerProvider>,
    );
    // down-1 first cell is at (1,2). Focus first, then click to toggle direction=down.
    const downCell = inputAt(container, 1, 2);
    act(() => {
      downCell.focus();
    });
    act(() => {
      // First click on the first-cell of down-1 sets direction=down.
      downCell.click();
    });
    const text = politeText(container);
    expect(text).toMatch(/mot vertical de \d+ lettres/);
    expect(text).toMatch(/: (point|[A-ZÉÈÀÇÊÎÔÛŸ])(, (point|[A-ZÉÈÀÇÊÎÔÛŸ]))*$/);
  });

  it('does NOT re-announce when moving within the same word', () => {
    const { container } = render(
      <AnnouncerProvider>
        <Grid puzzle={samplePuzzle} />
      </AnnouncerProvider>,
    );
    const c1 = inputAt(container, 1, 1);
    const c2 = inputAt(container, 1, 2);
    act(() => {
      c1.focus();
    });
    const polite = container.querySelector('[aria-live="polite"]')!;
    const textAfterFirst = polite.textContent;
    // Moving from (1,1) to (1,2) stays within across-2 (same def origin).
    // The word key (def-origin + arrow) is unchanged, so no re-announce should fire.
    act(() => {
      c2.focus();
    });
    expect(polite.textContent).toBe(textAfterFirst);
  });
});
