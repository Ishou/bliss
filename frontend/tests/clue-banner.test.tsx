import { render, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { ClueBanner } from '@/ui/components/keyboard/ClueBanner';
import type { Clue } from '@/ui/components/grid/useGridNavigation';

const makeClue = (text: string, len: number, dir: 'across' | 'down'): Clue => ({
  definition: {
    kind: 'definition',
    position: { row: 0, col: 0 },
    clues: [{ text, arrow: dir === 'across' ? 'right' : 'down' }],
  },
  clue: { text, arrow: dir === 'across' ? 'right' : 'down' },
  direction: dir,
  cells: Array.from({ length: len }, (_, i) => ({
    kind: 'letter',
    position: { row: 0, col: i + 1 },
    entry: '',
  })),
});

describe('ClueBanner', () => {
  it('renders the clue text and length', () => {
    const { getByText } = render(
      <ClueBanner
        clue={makeClue('Fruit jaune', 6, 'across')}
        alternateClue={null}
        onToggleDirection={() => undefined}
      />,
    );
    expect(getByText('Fruit jaune')).toBeTruthy();
    expect(getByText('6')).toBeTruthy();
  });

  it('renders the empty-state hint when clue is null', () => {
    const { getByText } = render(
      <ClueBanner clue={null} alternateClue={null} onToggleDirection={() => undefined} />,
    );
    expect(getByText(/Touchez une case/i)).toBeTruthy();
  });

  it('tapping the alt-clue chip fires onToggleDirection (and not on mousedown focus shift)', () => {
    const onToggleDirection = vi.fn();
    const { getByLabelText } = render(
      <ClueBanner
        clue={makeClue('Fruit jaune', 6, 'across')}
        alternateClue={makeClue('Vert', 4, 'down')}
        onToggleDirection={onToggleDirection}
      />,
    );
    const chip = getByLabelText(/Basculer/i);
    const md = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
    chip.dispatchEvent(md);
    expect(md.defaultPrevented).toBe(true);
    fireEvent.click(chip);
    expect(onToggleDirection).toHaveBeenCalled();
  });
});
