import { render, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { ClueBanner } from '@/ui/components/keyboard/ClueBanner';
import type { Clue } from '@/ui/components/grid/useGridNavigation';

// Build a Clue starting at (row, col0+1) with `len` letter cells laid out across or down.
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
    position:
      dir === 'across'
        ? { row, col: col0 + 1 + i }
        : { row: row + 1 + i, col: col0 },
    entry: '',
  })),
});

const noEntries = () => '';

describe('ClueBanner', () => {
  it('renders the active clue with an arrow icon (not text label)', () => {
    const { getByText, getByLabelText, container } = render(
      <ClueBanner
        clue={makeClue('Fruit jaune', 6, 'across')}
        alternateClue={null}
        onToggleDirection={() => undefined}
        getEntryAt={noEntries}
        focusedPosition={null}
      />,
    );
    expect(getByText('Fruit jaune')).toBeTruthy();
    expect(getByLabelText(/définition horizontale/i)).toBeTruthy();
    // No "horiz." / "vert." text labels.
    expect(container.textContent ?? '').not.toMatch(/horiz\.|vert\./);
  });

  it('renders the alt clue with its own arrow icon when present', () => {
    const { getByText, getAllByLabelText } = render(
      <ClueBanner
        clue={makeClue('Fruit jaune', 6, 'across')}
        alternateClue={makeClue('Vert', 4, 'down')}
        onToggleDirection={() => undefined}
        getEntryAt={noEntries}
        focusedPosition={null}
      />,
    );
    expect(getByText('Vert')).toBeTruthy();
    // Alt arrow has its own (alternative-prefixed) aria-label; active's is plain.
    expect(getAllByLabelText(/définition verticale/i).length).toBeGreaterThan(0);
  });

  it('letter preview shows filled letters, dots for empty, and an underline on the focused slot', () => {
    // Active clue at (0,1..5). Pre-fill col 2 with "A" and focus col 3.
    const clue = makeClue('Mot', 5, 'across');
    const entries = new Map<string, string>([['0,2', 'a']]);
    const { container } = render(
      <ClueBanner
        clue={clue}
        alternateClue={null}
        onToggleDirection={() => undefined}
        getEntryAt={(r, c) => entries.get(`${r},${c}`) ?? ''}
        focusedPosition={{ row: 0, col: 3 }}
      />,
    );
    // Letter row sits inside a [aria-hidden] span.
    const previewRow = container.querySelector('span[aria-hidden]');
    expect(previewRow).toBeTruthy();
    const slots = previewRow!.querySelectorAll(':scope > span');
    expect(slots.length).toBe(5);
    // Filled letter at index 1 (col 2) renders uppercase.
    expect(slots[1].textContent).toBe('A');
    // Empty slots render the dot placeholder.
    expect(slots[0].textContent).toBe('·');
    expect(slots[3].textContent).toBe('·');
    // The focused slot (col 3, index 2) carries the rose-underline class.
    const focusedClasses = slots[2].className;
    expect(focusedClasses).toMatch(/bd-b_|border-bottom|border_bottom/);
  });

  it('renders the empty-state hint when clue is null', () => {
    const { getByText } = render(
      <ClueBanner
        clue={null}
        alternateClue={null}
        onToggleDirection={() => undefined}
        getEntryAt={noEntries}
        focusedPosition={null}
      />,
    );
    expect(getByText(/Touchez une case/i)).toBeTruthy();
  });

  it('tapping the alt block fires onToggleDirection (and mousedown.preventDefault is preserved)', () => {
    const onToggleDirection = vi.fn();
    const { getByLabelText } = render(
      <ClueBanner
        clue={makeClue('Fruit jaune', 6, 'across')}
        alternateClue={makeClue('Vert', 4, 'down')}
        onToggleDirection={onToggleDirection}
        getEntryAt={noEntries}
        focusedPosition={null}
      />,
    );
    const altBlock = getByLabelText(/Basculer/i);
    const md = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
    altBlock.dispatchEvent(md);
    expect(md.defaultPrevented).toBe(true);
    fireEvent.click(altBlock);
    expect(onToggleDirection).toHaveBeenCalled();
  });
});
