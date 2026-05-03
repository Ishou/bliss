import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { EndGameModal } from '@/ui/components/lobby/EndGameModal';

// `EndGameModal` now delegates dialog scaffolding (role, aria-modal,
// focus trap, ESC-to-close, outside-click-to-close, focus restore) to
// the project-local `Dialog` primitive that wraps Ark UI / `@zag-js/dialog`
// (ADR-0002 §2). The state-machine driving Ark's dialog activates its
// activities (focus trap, dismiss layer, escape keydown listener) on a
// requestAnimationFrame after mount, so several assertions wait for the
// next frame before observing the side effects.

const flushDialog = async () => {
  // The zag dialog "open" state activities (focus trap, dismiss layer,
  // outside-click listener) are scheduled via rAF + setTimeout(0) after
  // the open transition. Wait long enough for both queues to drain
  // before observing the side effects.
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 50));
  });
};

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

  it('moves focus into the dialog on open (Ark focuses the content node)', async () => {
    render(
      <EndGameModal durationMs={1000} onPlayAgain={() => {}} onClose={() => {}} />,
    );
    // Ark Dialog focuses the content element itself by default
    // (`initialFocusEl` falls back to the dialog content). Wait for the
    // rAF-deferred focus-trap activity to run before asserting.
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toHaveFocus();
    });
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

  it('fires onClose on ESC keypress', async () => {
    const onClose = vi.fn();
    render(
      <EndGameModal durationMs={1000} onPlayAgain={() => {}} onClose={onClose} />,
    );
    await flushDialog();
    await act(async () => {
      fireEvent.keyDown(document, { key: 'Escape' });
      await new Promise((resolve) => setTimeout(resolve, 20));
    });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('fires onClose when the user clicks outside the dialog (backdrop)', async () => {
    const onClose = vi.fn();
    render(
      <EndGameModal durationMs={1000} onPlayAgain={() => {}} onClose={onClose} />,
    );
    await flushDialog();
    // Ark's outside-click detection listens on `pointerdown`, not
    // `click`. Fire on the backdrop element so the dismiss layer fires
    // its `onPointerDownOutside` handler.
    await act(async () => {
      fireEvent.pointerDown(screen.getByTestId('end-game-modal-backdrop'));
      await new Promise((resolve) => setTimeout(resolve, 20));
    });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('does not fire onClose when the dialog body is clicked', async () => {
    const onClose = vi.fn();
    render(
      <EndGameModal durationMs={1000} onPlayAgain={() => {}} onClose={onClose} />,
    );
    await flushDialog();
    fireEvent.pointerDown(screen.getByRole('dialog'));
    fireEvent.click(screen.getByRole('dialog'));
    expect(onClose).not.toHaveBeenCalled();
  });

  it('restores focus to the previously focused element on unmount', async () => {
    // Place a button in the DOM, focus it, then mount + unmount the modal.
    const trigger = document.createElement('button');
    trigger.textContent = 'open';
    document.body.appendChild(trigger);
    trigger.focus();
    expect(trigger).toHaveFocus();

    const { unmount } = render(
      <EndGameModal durationMs={1000} onPlayAgain={() => {}} onClose={() => {}} />,
    );
    // Focus moved into the dialog on open.
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toHaveFocus();
    });
    unmount();
    // Ark restores focus to the previously focused element (`finalFocusEl`
    // defaults to the trigger / `document.activeElement` snapshot taken
    // at open time).
    await waitFor(() => {
      expect(trigger).toHaveFocus();
    });
    document.body.removeChild(trigger);
  });
});
