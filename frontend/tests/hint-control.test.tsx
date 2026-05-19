import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { HintControl } from '@/ui/components/grid/HintControl';

// No AuthProvider in tree: gate is inactive; hook branches tested in hint-gate.test.tsx.

const focusedAt = (row: number, column: number, isLocked = false) =>
  () => ({ row, column, isLocked });

function renderWith(overrides: Partial<React.ComponentProps<typeof HintControl>> = {}) {
  const onRequest = vi.fn();
  const utils = render(
    <HintControl
      hintsRemaining={3}
      hintsAllowed={3}
      exhausted={false}
      pending={false}
      lastResult={null}
      errorMessage={null}
      getFocusedCell={focusedAt(2, 4)}
      onRequest={onRequest}
      {...overrides}
    />,
  );
  return { ...utils, onRequest };
}

const labelOf = (remaining: number, allowed: number) =>
  `Indice (${remaining} / ${allowed})`;

describe('HintControl', () => {
  it('renders the label "Indice (2 / 3)" for hintsRemaining=2, hintsAllowed=3', () => {
    renderWith({ hintsRemaining: 2, hintsAllowed: 3 });
    expect(
      screen.getByRole('button', { name: labelOf(2, 3) }),
    ).toHaveTextContent('Indice (2 / 3)');
  });

  it('renders the label "Indice (3 / 3)" and the button is enabled when budget is full', () => {
    renderWith({ hintsRemaining: 3, hintsAllowed: 3 });
    const button = screen.getByRole('button', { name: labelOf(3, 3) });
    expect(button).toHaveTextContent('Indice (3 / 3)');
    expect(button).not.toBeDisabled();
  });

  it('disables the button and surfaces the budget-exhausted tooltip when hintsRemaining=0', () => {
    renderWith({ hintsRemaining: 0, hintsAllowed: 3 });
    const button = screen.getByRole('button', { name: labelOf(0, 3) });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute(
      'title',
      'Vous avez utilisé tous vos indices pour cette grille.',
    );
  });

  it('exposes the default tooltip when budget remains', () => {
    renderWith({ hintsRemaining: 2, hintsAllowed: 3 });
    expect(
      screen.getByRole('button', { name: labelOf(2, 3) }),
    ).toHaveAttribute('title', 'Demander un indice');
  });

  it('calls onRequest with the focused cell coordinates on click', () => {
    const { onRequest } = renderWith({ getFocusedCell: focusedAt(2, 4) });
    fireEvent.click(screen.getByRole('button', { name: labelOf(3, 3) }));
    expect(onRequest).toHaveBeenCalledWith(2, 4);
  });

  it('does not call onRequest when no cell is focused', () => {
    const { onRequest } = renderWith({ getFocusedCell: () => null });
    fireEvent.click(screen.getByRole('button', { name: labelOf(3, 3) }));
    expect(onRequest).not.toHaveBeenCalled();
  });

  it('does not call onRequest when the focused cell is already locked', () => {
    const { onRequest } = renderWith({ getFocusedCell: focusedAt(2, 4, true) });
    fireEvent.click(screen.getByRole('button', { name: labelOf(3, 3) }));
    expect(onRequest).not.toHaveBeenCalled();
  });

  it('disables the button when exhausted', () => {
    renderWith({ exhausted: true });
    expect(
      screen.getByRole('button', { name: labelOf(3, 3) }),
    ).toBeDisabled();
  });

  it('disables the button while pending', () => {
    renderWith({ pending: true });
    expect(
      screen.getByRole('button', { name: labelOf(3, 3) }),
    ).toBeDisabled();
  });

  it('renders a success status pill with the revealed letter', () => {
    renderWith({ lastResult: { row: 2, column: 4, letter: 'P' } });
    expect(screen.getByRole('status')).toHaveTextContent('Lettre révélée : P');
  });

  it('renders the error message when supplied', () => {
    renderWith({ errorMessage: 'Indices épuisés', exhausted: true });
    expect(screen.getByRole('status')).toHaveTextContent('Indices épuisés');
  });
});
