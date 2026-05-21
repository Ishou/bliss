import { render, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { ActionRow } from '@/ui/components/keyboard/ActionRow';

const baseProps = {
  onPrev: () => undefined,
  onNext: () => undefined,
  onHint: () => undefined,
  hintRemaining: 3,
  hintAllowed: 3,
  hintDisabled: false,
};

describe('ActionRow', () => {
  it('renders Préc, Indice (with counter), Suiv', () => {
    const { getByLabelText, getByText } = render(<ActionRow {...baseProps} />);
    expect(getByLabelText('Indice précédent')).toBeTruthy();
    expect(getByLabelText('Indice suivant')).toBeTruthy();
    expect(getByText(/Indice/)).toBeTruthy();
    expect(getByText(/3.*\/.*3/)).toBeTruthy();
  });

  it('clicking calls the correct callbacks', () => {
    const onPrev = vi.fn();
    const onNext = vi.fn();
    const onHint = vi.fn();
    const { getByLabelText } = render(
      <ActionRow {...baseProps} onPrev={onPrev} onNext={onNext} onHint={onHint} />,
    );
    fireEvent.click(getByLabelText('Indice précédent'));
    fireEvent.click(getByLabelText('Indice suivant'));
    fireEvent.click(getByLabelText(/Demander un indice/));
    expect(onPrev).toHaveBeenCalled();
    expect(onNext).toHaveBeenCalled();
    expect(onHint).toHaveBeenCalled();
  });

  it('hintDisabled=true blocks the hint button onPress', () => {
    const onHint = vi.fn();
    const { getByLabelText } = render(
      <ActionRow {...baseProps} onHint={onHint} hintDisabled />,
    );
    fireEvent.click(getByLabelText(/Demander un indice/));
    expect(onHint).not.toHaveBeenCalled();
  });
});
