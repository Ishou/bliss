import { render, screen, fireEvent, act } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { EndGameModal } from '@/ui/components/lobby/EndGameModal';

describe('EndGameModal', () => {
  it('renders the duration in MM:SS', () => {
    render(
      <EndGameModal durationMs={125_000} onPlayAgain={() => {}} onClose={() => {}} />,
    );
    expect(screen.getByTestId('end-game-modal-duration')).toHaveTextContent('02:05');
  });

  it('renders the duration in HH:MM:SS past 1 hour', () => {
    render(
      <EndGameModal
        durationMs={60 * 60 * 1000 + 23 * 60 * 1000 + 45 * 1000}
        onPlayAgain={() => {}}
        onClose={() => {}}
      />,
    );
    expect(screen.getByTestId('end-game-modal-duration')).toHaveTextContent('01:23:45');
  });

  it('uses dialog role + aria-modal + an accessible label', () => {
    render(
      <EndGameModal durationMs={42_000} onPlayAgain={() => {}} onClose={() => {}} />,
    );
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    // aria-labelledby points at the title; the accessible name resolves
    // through that link to the title's text.
    expect(dialog).toHaveAccessibleName(/Bravo.*Grille terminée/);
  });

  it('places focus on the dialog on open', () => {
    render(
      <EndGameModal durationMs={1000} onPlayAgain={() => {}} onClose={() => {}} />,
    );
    expect(screen.getByRole('dialog')).toHaveFocus();
  });

  it('fires onPlayAgain when "Rejouer" is clicked', () => {
    const onPlayAgain = vi.fn();
    render(
      <EndGameModal durationMs={1000} onPlayAgain={onPlayAgain} onClose={() => {}} />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Rejouer' }));
    expect(onPlayAgain).toHaveBeenCalledTimes(1);
  });

  it('fires onClose when "Fermer" is clicked', () => {
    const onClose = vi.fn();
    render(
      <EndGameModal durationMs={1000} onPlayAgain={() => {}} onClose={onClose} />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Fermer' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('fires onClose on ESC keypress', () => {
    const onClose = vi.fn();
    render(
      <EndGameModal durationMs={1000} onPlayAgain={() => {}} onClose={onClose} />,
    );
    act(() => {
      fireEvent.keyDown(document, { key: 'Escape' });
    });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('fires onClose when the backdrop is clicked', () => {
    const onClose = vi.fn();
    render(
      <EndGameModal durationMs={1000} onPlayAgain={() => {}} onClose={onClose} />,
    );
    fireEvent.click(screen.getByTestId('end-game-modal-backdrop'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('does not fire onClose when the dialog body is clicked', () => {
    const onClose = vi.fn();
    render(
      <EndGameModal durationMs={1000} onPlayAgain={() => {}} onClose={onClose} />,
    );
    fireEvent.click(screen.getByRole('dialog'));
    expect(onClose).not.toHaveBeenCalled();
  });

  it('traps focus: Tab from the last button cycles back to the first', () => {
    render(
      <EndGameModal durationMs={1000} onPlayAgain={() => {}} onClose={() => {}} />,
    );
    const closeBtn = screen.getByRole('button', { name: 'Fermer' });
    const playAgainBtn = screen.getByRole('button', { name: 'Rejouer' });

    // Focus the last focusable (Rejouer is last in DOM order); Tab must
    // wrap back to the first (Fermer).
    playAgainBtn.focus();
    act(() => {
      fireEvent.keyDown(document, { key: 'Tab' });
    });
    expect(closeBtn).toHaveFocus();
  });

  it('traps focus: Shift+Tab from the dialog wraps to the last focusable', () => {
    render(
      <EndGameModal durationMs={1000} onPlayAgain={() => {}} onClose={() => {}} />,
    );
    const dialog = screen.getByRole('dialog');
    const playAgainBtn = screen.getByRole('button', { name: 'Rejouer' });
    // Dialog has the initial focus; Shift+Tab must wrap to the last.
    expect(dialog).toHaveFocus();
    act(() => {
      fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });
    });
    expect(playAgainBtn).toHaveFocus();
  });

  it('restores focus to the previously focused element on unmount', () => {
    // Place a button in the DOM, focus it, then mount + unmount the modal.
    const trigger = document.createElement('button');
    trigger.textContent = 'open';
    document.body.appendChild(trigger);
    trigger.focus();
    expect(trigger).toHaveFocus();

    const { unmount } = render(
      <EndGameModal durationMs={1000} onPlayAgain={() => {}} onClose={() => {}} />,
    );
    // Focus moved to the dialog on open.
    expect(screen.getByRole('dialog')).toHaveFocus();
    unmount();
    expect(trigger).toHaveFocus();
    document.body.removeChild(trigger);
  });
});
