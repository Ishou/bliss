import { render, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
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

const fullProps = {
  onLetter: noop,
  onBackspace: noop,
  onToggleDirection: noop,
  onPrevClue: noop,
  onNextClue: noop,
  onRequestHint: noop,
  activeClue: stubClue('Fruit', 5) as Clue | null,
  alternateClue: null as Clue | null,
  hintRemaining: 3,
  hintAllowed: 3,
  hintExhausted: false,
  hintPending: false,
  getFocusedCell: () => ({ row: 0, column: 0, isLocked: false }),
  getEntryAt: () => '',
  focusedPosition: null as { row: number; col: number } | null,
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

  it('renders the direction key in the bottom row', () => {
    const { getByLabelText } = render(<MobileKeyboard {...fullProps} />);
    expect(getByLabelText('Changer de sens')).toBeTruthy();
  });

  it('clicking the direction key calls onToggleDirection', () => {
    const onToggleDirection = vi.fn();
    const { getByLabelText } = render(
      <MobileKeyboard {...fullProps} onToggleDirection={onToggleDirection} />,
    );
    fireEvent.click(getByLabelText('Changer de sens'));
    expect(onToggleDirection).toHaveBeenCalled();
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
