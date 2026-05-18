import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { AuthClient, WhoAmIResult } from '@/application/auth';
import { AuthProvider, HintGate } from '@/ui/components/auth';

const USER: WhoAmIResult = {
  userId: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  displayName: 'Lapin 472',
};

function makeClient(opts: { whoami: WhoAmIResult | null | Error; latch?: Promise<void> }): AuthClient {
  return {
    async whoami() {
      if (opts.latch) await opts.latch;
      if (opts.whoami instanceof Error) throw opts.whoami;
      return opts.whoami;
    },
    async getMe() { throw new Error('not used'); },
    async updateMe() {},
    async deleteMe() {},
    async logout() {},
    signInUrl(provider, returnTo) {
      return `https://auth.test/v1/auth/${provider}/login?return_to=${encodeURIComponent(returnTo)}`;
    },
  };
}

describe('HintGate', () => {
  it('disables the child + sets the sign-in tooltip when status=anon', async () => {
    render(
      <AuthProvider authClient={makeClient({ whoami: null })} getPseudonym={() => 'Renard 423'}>
        <HintGate>
          <button type="button" data-testid="hint-button">Indice</button>
        </HintGate>
      </AuthProvider>,
    );
    const button = await screen.findByTestId('hint-button');
    await waitFor(() => expect(button).toBeDisabled());
    expect(button).toHaveAttribute('title', 'Connectez-vous pour utiliser les indices.');
    expect(button).toHaveAttribute('aria-disabled', 'true');
  });

  it('disables with "Chargement…" tooltip while status=loading', () => {
    // Never-resolving whoami keeps state pinned at loading.
    const latch = new Promise<void>(() => {});
    render(
      <AuthProvider authClient={makeClient({ whoami: null, latch })} getPseudonym={() => 'Renard 423'}>
        <HintGate>
          <button type="button" data-testid="hint-button">Indice</button>
        </HintGate>
      </AuthProvider>,
    );
    const button = screen.getByTestId('hint-button');
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', 'Chargement…');
    expect(button).toHaveAttribute('aria-disabled', 'true');
  });

  it('renders the child unchanged when status=authed', async () => {
    render(
      <AuthProvider authClient={makeClient({ whoami: USER })} getPseudonym={() => 'Lapin 472'}>
        <HintGate>
          <button type="button" data-testid="hint-button" disabled={false} title="Demander un indice">
            Indice
          </button>
        </HintGate>
      </AuthProvider>,
    );
    // Re-query each iteration: the gate's loading branch clones the
    // child, then the authed branch swaps it back to the unmodified
    // element, so the DOM node identity changes across the transition.
    await waitFor(() => {
      const button = screen.getByTestId('hint-button');
      expect(button).not.toBeDisabled();
      expect(button).toHaveAttribute('title', 'Demander un indice');
      expect(button).not.toHaveAttribute('aria-disabled', 'true');
    });
  });

  it('passes through unchanged when rendered outside an AuthProvider', () => {
    render(
      <HintGate>
        <button type="button" data-testid="hint-button" title="Demander un indice">
          Indice
        </button>
      </HintGate>,
    );
    const button = screen.getByTestId('hint-button');
    expect(button).not.toBeDisabled();
    expect(button).toHaveAttribute('title', 'Demander un indice');
  });
});
