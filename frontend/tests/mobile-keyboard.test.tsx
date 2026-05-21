import { render, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { MobileKeyboard } from '@/ui/components/keyboard';

const noop = () => undefined;

const baseProps = {
  onLetter: noop,
  onBackspace: noop,
};

describe('MobileKeyboard letters + backspace', () => {
  it('renders all 26 letter buttons', () => {
    const { getAllByRole } = render(<MobileKeyboard {...baseProps} />);
    const buttons = getAllByRole('button');
    const labels = buttons.map((b) => b.getAttribute('aria-label')).filter(Boolean);
    for (const ch of 'ABCDEFGHIJKLMNOPQRSTUVWXYZ') {
      expect(labels).toContain(`Lettre ${ch}`);
    }
  });

  it('clicking a letter calls onLetter with that character', () => {
    const onLetter = vi.fn();
    const { getByLabelText } = render(
      <MobileKeyboard {...baseProps} onLetter={onLetter} />,
    );
    fireEvent.click(getByLabelText('Lettre E'));
    expect(onLetter).toHaveBeenCalledWith('E');
  });

  it('clicking backspace calls onBackspace', () => {
    const onBackspace = vi.fn();
    const { getByLabelText } = render(
      <MobileKeyboard {...baseProps} onBackspace={onBackspace} />,
    );
    fireEvent.click(getByLabelText('Effacer'));
    expect(onBackspace).toHaveBeenCalled();
  });

  it('panel has role=group with accessible name', () => {
    const { getByRole } = render(<MobileKeyboard {...baseProps} />);
    expect(getByRole('group', { name: 'Clavier mots fléchés' })).toBeTruthy();
  });
});
