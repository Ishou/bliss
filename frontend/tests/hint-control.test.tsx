import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { HintControl } from '@/ui/components/grid/HintControl';

const noopGetWord = () => 'forêt';

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
      getCurrentWord={noopGetWord}
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

  it('calls onRequest with the active word on click', () => {
    const { onRequest } = renderWith();
    fireEvent.click(
      screen.getByRole('button', { name: 'Demander un indice' }),
    );
    expect(onRequest).toHaveBeenCalledWith('forêt');
  });

  it('does not call onRequest when getCurrentWord returns null', () => {
    const { onRequest } = renderWith({ getCurrentWord: () => null });
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

  it('renders a success status pill when lastResult.exists is true', () => {
    renderWith({ lastResult: { word: 'forêt', exists: true } });
    expect(screen.getByRole('status')).toHaveTextContent('« forêt » existe');
  });

  it('renders a failure status pill when lastResult.exists is false', () => {
    renderWith({ lastResult: { word: 'xyz', exists: false } });
    expect(screen.getByRole('status')).toHaveTextContent('« xyz » introuvable');
  });

  it('renders the error message when supplied', () => {
    renderWith({ errorMessage: 'Indices épuisés', exhausted: true });
    expect(screen.getByRole('status')).toHaveTextContent('Indices épuisés');
  });
});
