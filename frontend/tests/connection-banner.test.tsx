import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { ConnectionBanner } from '@/ui/components/lobby/ConnectionBanner';

// Wave H PR #17. The banner is purely prop-driven so each state is one
// render with no setup. Behavior under test: the connected branch
// renders no chrome (returning `null` is the contract), and each of
// the three unhealthy states renders distinct French copy with the
// correct ARIA semantics for assistive tech (WCAG 2.2 AA per CLAUDE.md).

describe('ConnectionBanner', () => {
  it('renders nothing when the socket is connected', () => {
    const { container } = render(<ConnectionBanner state="connected" />);
    // No element at all — the component returns `null` so the lobby
    // page has no banner taking layout space on the happy path.
    expect(container.firstChild).toBeNull();
    expect(screen.queryByTestId('connection-banner')).toBeNull();
  });

  it('renders the connecting copy with role=status / aria-live=polite', () => {
    render(<ConnectionBanner state="connecting" />);
    const banner = screen.getByTestId('connection-banner');
    expect(banner).toHaveTextContent('Connexion en cours');
    expect(banner).toHaveAttribute('role', 'status');
    expect(banner).toHaveAttribute('aria-live', 'polite');
    expect(banner).toHaveAttribute('data-state', 'connecting');
  });

  it('renders the disconnected copy distinct from the other variants', () => {
    render(<ConnectionBanner state="disconnected" />);
    const banner = screen.getByTestId('connection-banner');
    expect(banner).toHaveTextContent('Connexion perdue');
    // Distinctness check — disconnected must not borrow connecting/
    // reconnecting copy. Catches a regression where COPY entries get
    // accidentally aliased.
    expect(banner).not.toHaveTextContent('en cours');
    expect(banner).not.toHaveTextContent('Reconnexion');
    expect(banner).toHaveAttribute('data-state', 'disconnected');
  });

  it('exposes a Recharger CTA only in the disconnected state', () => {
    const { rerender } = render(<ConnectionBanner state="disconnected" />);
    expect(
      screen.getByRole('button', { name: 'Recharger' }),
    ).toBeInTheDocument();
    rerender(<ConnectionBanner state="reconnecting" />);
    expect(
      screen.queryByRole('button', { name: 'Recharger' }),
    ).toBeNull();
    rerender(<ConnectionBanner state="connecting" />);
    expect(
      screen.queryByRole('button', { name: 'Recharger' }),
    ).toBeNull();
  });

  it('renders the reconnecting copy with a retry indicator marked aria-hidden', () => {
    render(<ConnectionBanner state="reconnecting" />);
    const banner = screen.getByTestId('connection-banner');
    expect(banner).toHaveTextContent('Reconnexion');
    // The indicator carries no semantic meaning — copy already conveys
    // the state — so it must be hidden from the accessibility tree.
    const indicator = banner.querySelector('[aria-hidden="true"]');
    expect(indicator).not.toBeNull();
    expect(indicator?.textContent).toBe('↻');
    expect(banner).toHaveAttribute('data-state', 'reconnecting');
  });
});
