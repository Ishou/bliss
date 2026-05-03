import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Dialog } from '@/ui/components/primitives';

// Smoke tests for the project-local `Dialog` primitive (ADR-0002 §2).
// The detailed behavioural coverage lives in `end-game-modal.test.tsx`
// (the first real consumer); these tests guard the wrapper's contract:
//
//   * `open={false}` does not render the dialog parts
//   * `open={true}` renders a `role="dialog"` with `aria-modal="true"`
//     and a label resolved from the `title` prop
//   * `onClose` fires when the dismiss layer triggers (ESC keypress)
//
// Outside-click + focus-trap mechanics are tested in `end-game-modal`
// against the same primitive and are not duplicated here.

const flushDialog = async () => {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 50));
  });
};

describe('Dialog', () => {
  it('does not render dialog content when open=false', () => {
    render(
      <Dialog open={false} onClose={() => {}} title="Bonjour">
        <p>Body</p>
      </Dialog>,
    );
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('renders the dialog content with role=dialog + aria-modal when open', () => {
    render(
      <Dialog open onClose={() => {}} title="Bonjour">
        <p>Body</p>
      </Dialog>,
    );
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAccessibleName('Bonjour');
    expect(screen.getByText('Body')).toBeInTheDocument();
  });

  it('fires onClose on ESC keypress', async () => {
    const onClose = vi.fn();
    render(
      <Dialog open onClose={onClose} title="Bonjour">
        <p>Body</p>
      </Dialog>,
    );
    await flushDialog();
    await act(async () => {
      fireEvent.keyDown(document, { key: 'Escape' });
      await new Promise((resolve) => setTimeout(resolve, 20));
    });
    await waitFor(() => {
      expect(onClose).toHaveBeenCalledTimes(1);
    });
  });
});
