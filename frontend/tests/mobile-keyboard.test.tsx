import { render, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import type { Puzzle } from '@/domain';
import type { Clue } from '@/ui/components/grid/useGridNavigation';
import { MobileKeyboard } from '@/ui/components/keyboard';

const noop = () => undefined;

const stubClue = (text: string, len: number): Clue => ({
  definition: {
    kind: 'definition',
    position: { row: 0, col: 0 },
    clues: [{ text, arrow: 'right' }],
  },
  clue: { text, arrow: 'right' },
  direction: 'across',
  cells: Array.from({ length: len }, (_, i) => ({
    kind: 'letter',
    position: { row: 0, col: i + 1 },
    entry: '',
  })),
});

const stubPuzzle: Puzzle = {
  id: 'test',
  title: 't',
  language: 'fr',
  width: 5,
  height: 5,
  hintsAllowed: 3,
  hintsRemaining: 3,
  cells: [],
};

const fullProps = {
  onLetter: noop,
  onBackspace: noop,
  onToggleDirection: noop,
  onPrevClue: noop,
  onNextClue: noop,
  onRequestHint: noop,
  onMoveCursor: noop as (d: 'left' | 'right' | 'up' | 'down') => void,
  activeClue: stubClue('Fruit', 5) as Clue | null,
  alternateClue: null as Clue | null,
  hintRemaining: 3,
  hintAllowed: 3,
  hintExhausted: false,
  hintPending: false,
  getFocusedCell: () => ({ row: 0, column: 0, isLocked: false }),
  getEntryAt: () => '',
  focusedPosition: null as { row: number; col: number } | null,
  puzzle: stubPuzzle,
  scale: 1,
  positionX: 0,
  positionY: 0,
  contentWidth: 200,
  contentHeight: 200,
};

describe('MobileKeyboard letters + backspace', () => {
  it('renders all 26 letter buttons', () => {
    const { getAllByRole } = render(<MobileKeyboard {...fullProps} />);
    const buttons = getAllByRole('button');
    const labels = buttons.map((b) => b.getAttribute('aria-label')).filter(Boolean);
    for (const ch of 'ABCDEFGHIJKLMNOPQRSTUVWXYZ') {
      expect(labels).toContain(`Lettre ${ch}`);
    }
  });

  it('clicking a letter calls onLetter with that character', () => {
    const onLetter = vi.fn();
    const { getByLabelText } = render(
      <MobileKeyboard {...fullProps} onLetter={onLetter} />,
    );
    fireEvent.click(getByLabelText('Lettre E'));
    expect(onLetter).toHaveBeenCalledWith('E');
  });

  it('clicking backspace calls onBackspace', () => {
    const onBackspace = vi.fn();
    const { getByLabelText } = render(
      <MobileKeyboard {...fullProps} onBackspace={onBackspace} />,
    );
    fireEvent.click(getByLabelText('Effacer'));
    expect(onBackspace).toHaveBeenCalled();
  });

  it('panel has role=group with accessible name', () => {
    const { getByRole } = render(<MobileKeyboard {...fullProps} />);
    expect(getByRole('group', { name: 'Clavier mots fléchés' })).toBeTruthy();
  });

  it('publishes its measured height as --mobile-kb-height on document root', async () => {
    const { unmount } = render(<MobileKeyboard {...fullProps} />);
    await new Promise((r) => requestAnimationFrame(() => r(null)));
    const val = document.documentElement.style.getPropertyValue('--mobile-kb-height');
    expect(val).toMatch(/^\d+px$/);
    unmount();
    expect(document.documentElement.style.getPropertyValue('--mobile-kb-height')).toBe('');
  });
});

describe('MobileKeyboard banner + action row + direction', () => {
  it('shows the clue banner with active clue text', () => {
    const { getByText } = render(<MobileKeyboard {...fullProps} />);
    expect(getByText('Fruit')).toBeTruthy();
  });

  it('does not render a direction-toggle key in the bottom row (handled by banner alt-chip)', () => {
    const { queryByLabelText } = render(<MobileKeyboard {...fullProps} />);
    expect(queryByLabelText('Changer de sens')).toBeNull();
  });

  it('renders the Indice button in the bottom row next to backspace', () => {
    const { getByLabelText, getAllByRole } = render(<MobileKeyboard {...fullProps} />);
    const hintBtn = getByLabelText(/Demander un indice/);
    const eraseBtn = getByLabelText('Effacer');
    expect(hintBtn).toBeTruthy();
    expect(eraseBtn).toBeTruthy();
    // Indice should immediately precede Effacer in the DOM order (same row).
    const buttons = getAllByRole('button');
    const hintIdx = buttons.indexOf(hintBtn);
    const eraseIdx = buttons.indexOf(eraseBtn);
    expect(eraseIdx).toBe(hintIdx + 1);
  });

  it('clicking the hint button calls onRequestHint', () => {
    const onRequestHint = vi.fn();
    const { getByLabelText } = render(
      <MobileKeyboard {...fullProps} onRequestHint={onRequestHint} />,
    );
    fireEvent.click(getByLabelText(/Demander un indice/));
    expect(onRequestHint).toHaveBeenCalled();
  });

  it('clicking Suiv. ▶ calls onNextClue', () => {
    const onNextClue = vi.fn();
    const { getByLabelText } = render(
      <MobileKeyboard {...fullProps} onNextClue={onNextClue} />,
    );
    fireEvent.click(getByLabelText('Indice suivant'));
    expect(onNextClue).toHaveBeenCalled();
  });

  it('clicking ◀ Préc. calls onPrevClue', () => {
    const onPrevClue = vi.fn();
    const { getByLabelText } = render(
      <MobileKeyboard {...fullProps} onPrevClue={onPrevClue} />,
    );
    fireEvent.click(getByLabelText('Indice précédent'));
    expect(onPrevClue).toHaveBeenCalled();
  });

  it('hint button disabled when exhausted, pending, or no active clue', () => {
    for (const overrides of [
      { hintExhausted: true },
      { hintPending: true },
      { activeClue: null as unknown as Clue },
    ]) {
      const { getByLabelText, unmount } = render(
        <MobileKeyboard {...fullProps} {...overrides} />,
      );
      const btn = getByLabelText(/Demander un indice/);
      expect(btn.getAttribute('aria-disabled')).toBe('true');
      unmount();
    }
  });

  it('hint click is a no-op when getFocusedCell returns a locked cell', () => {
    const onRequestHint = vi.fn();
    const { getByLabelText } = render(
      <MobileKeyboard
        {...fullProps}
        getFocusedCell={() => ({ row: 0, column: 0, isLocked: true })}
        onRequestHint={onRequestHint}
      />,
    );
    fireEvent.click(getByLabelText(/Demander un indice/));
    expect(onRequestHint).not.toHaveBeenCalled();
  });

  it('hint click is a no-op when getFocusedCell returns null', () => {
    const onRequestHint = vi.fn();
    const { getByLabelText } = render(
      <MobileKeyboard
        {...fullProps}
        getFocusedCell={() => null}
        onRequestHint={onRequestHint}
      />,
    );
    fireEvent.click(getByLabelText(/Demander un indice/));
    expect(onRequestHint).not.toHaveBeenCalled();
  });

  it('shows empty-state hint when activeClue is null', () => {
    const { getByText } = render(<MobileKeyboard {...fullProps} activeClue={null} />);
    expect(getByText(/Touchez une case/i)).toBeTruthy();
  });
});

describe('MobileKeyboard arrow-key row', () => {
  it('renders the 4 cursor-arrow keys with French aria-labels', () => {
    const { getByLabelText } = render(<MobileKeyboard {...fullProps} />);
    expect(getByLabelText('Curseur gauche')).toBeTruthy();
    expect(getByLabelText('Curseur haut')).toBeTruthy();
    expect(getByLabelText('Curseur bas')).toBeTruthy();
    expect(getByLabelText('Curseur droite')).toBeTruthy();
  });

  it('clicking each arrow key calls onMoveCursor with the matching direction', () => {
    const onMoveCursor = vi.fn();
    const { getByLabelText } = render(
      <MobileKeyboard {...fullProps} onMoveCursor={onMoveCursor} />,
    );
    fireEvent.click(getByLabelText('Curseur gauche'));
    fireEvent.click(getByLabelText('Curseur haut'));
    fireEvent.click(getByLabelText('Curseur bas'));
    fireEvent.click(getByLabelText('Curseur droite'));
    expect(onMoveCursor.mock.calls.map((c) => c[0])).toEqual(['left', 'up', 'down', 'right']);
  });
});
