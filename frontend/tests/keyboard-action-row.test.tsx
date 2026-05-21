import { render, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import type { Puzzle } from '@/domain';
import { ActionRow } from '@/ui/components/keyboard/ActionRow';

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

const baseProps = {
  onPrev: () => undefined,
  onNext: () => undefined,
  puzzle: stubPuzzle,
  scale: 1,
  positionX: 0,
  positionY: 0,
  contentWidth: 200,
  contentHeight: 200,
};

describe('ActionRow', () => {
  it('renders Préc and Suiv buttons', () => {
    const { getByLabelText } = render(<ActionRow {...baseProps} />);
    expect(getByLabelText('Indice précédent')).toBeTruthy();
    expect(getByLabelText('Indice suivant')).toBeTruthy();
  });

  it('does not render the Indice hint button (moved to bottom row)', () => {
    const { queryByLabelText } = render(<ActionRow {...baseProps} />);
    expect(queryByLabelText(/Demander un indice/)).toBeNull();
  });

  it('reserves a minimap placeholder slot at scale 1', () => {
    const { queryByLabelText } = render(<ActionRow {...baseProps} />);
    // At rest, the minimap is a non-interactive placeholder div without role="img".
    expect(queryByLabelText(/Aperçu de la grille/)).toBeNull();
  });

  it('renders the minimap once zoomed in', () => {
    const { getByLabelText } = render(<ActionRow {...baseProps} scale={2} />);
    expect(getByLabelText(/Aperçu de la grille/)).toBeTruthy();
  });

  it('clicking Préc / Suiv calls the correct callbacks', () => {
    const onPrev = vi.fn();
    const onNext = vi.fn();
    const { getByLabelText } = render(
      <ActionRow {...baseProps} onPrev={onPrev} onNext={onNext} />,
    );
    fireEvent.click(getByLabelText('Indice précédent'));
    fireEvent.click(getByLabelText('Indice suivant'));
    expect(onPrev).toHaveBeenCalled();
    expect(onNext).toHaveBeenCalled();
  });
});
