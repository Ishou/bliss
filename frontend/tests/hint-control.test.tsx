import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { HintControl } from '@/ui/components/grid/HintControl';

// HintGate (wrapped inside HintControl) is a pass-through when there's
// no AuthProvider in the tree — existing assertions stay valid. The
// gate's anon / loading / authed branches are covered separately in
// `hint-gate.test.tsx`.

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

describe('HintControl', () => {
  it('shows the remaining/allowed badge', () => {
    renderWith({ hintsRemaining: 2, hintsAllowed: 3 });
    expect(
      screen.getByLabelText('2 sur 3 indices restants'),
    ).toHaveTextContent('2/3');
  });

  it('renders the visible "Indice" label inside the pill', () => {
    renderWith();
    expect(
      screen.getByRole('button', { name: 'Demander un indice' }),
    ).toHaveTextContent('Indice');
  });

  it('exposes a tooltip via the title attribute', () => {
    renderWith();
    expect(
      screen.getByRole('button', { name: 'Demander un indice' }),
    ).toHaveAttribute('title', 'Demander un indice');
  });

  it('calls onRequest with the focused cell coordinates on click', () => {
    const { onRequest } = renderWith({ getFocusedCell: focusedAt(2, 4) });
    fireEvent.click(
      screen.getByRole('button', { name: 'Demander un indice' }),
    );
    expect(onRequest).toHaveBeenCalledWith(2, 4);
  });

  it('does not call onRequest when no cell is focused', () => {
    const { onRequest } = renderWith({ getFocusedCell: () => null });
    fireEvent.click(
      screen.getByRole('button', { name: 'Demander un indice' }),
    );
    expect(onRequest).not.toHaveBeenCalled();
  });

  it('does not call onRequest when the focused cell is already locked', () => {
    const { onRequest } = renderWith({
      getFocusedCell: focusedAt(2, 4, true),
    });
    fireEvent.click(
      screen.getByRole('button', { name: 'Demander un indice' }),
    );
    expect(onRequest).not.toHaveBeenCalled();
  });

  it('disables the button when exhausted', () => {
    renderWith({ exhausted: true });
    expect(
      screen.getByRole('button', { name: 'Demander un indice' }),
    ).toBeDisabled();
  });

  it('disables the button while pending', () => {
    renderWith({ pending: true });
    expect(
      screen.getByRole('button', { name: 'Demander un indice' }),
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
