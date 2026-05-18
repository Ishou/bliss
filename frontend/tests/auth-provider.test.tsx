import { act, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AuthClient, WhoAmIResult } from '@/application/auth';
import { AuthProvider, useAuth } from '@/ui/components/auth';

const USER_ID = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b';

interface FakeAuthClient extends AuthClient {
  readonly _calls: {
    whoami: number;
    updateMe: string[];
  };
}

function fakeAuthClient(opts: {
  whoamiSeq: ReadonlyArray<WhoAmIResult | null | Error>;
  updateMeImpl?: (n: string) => Promise<void>;
}): FakeAuthClient {
  let i = 0;
  const calls = { whoami: 0, updateMe: [] as string[] };
  return {
    _calls: calls,
    async whoami() {
      calls.whoami += 1;
      const next = opts.whoamiSeq[Math.min(i, opts.whoamiSeq.length - 1)];
      i += 1;
      if (next instanceof Error) throw next;
      return next;
    },
    async getMe() { throw new Error('not used'); },
    async updateMe(n: string) {
      calls.updateMe.push(n);
      if (opts.updateMeImpl) return opts.updateMeImpl(n);
    },
    async deleteMe() {},
    async logout() {},
    signInUrl(provider, returnTo) {
      return `https://auth.test/v1/auth/${provider}/login?return_to=${encodeURIComponent(returnTo)}`;
    },
  };
}

function Probe() {
  const { state } = useAuth();
  return (
    <div>
      <span data-testid="status">{state.status}</span>
      {state.status === 'authed' ? (
        <span data-testid="display">{state.whoami.displayName}</span>
      ) : null}
    </div>
  );
}

describe('AuthProvider', () => {
  beforeEach(() => {
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      value: 'visible',
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders status=anon when whoami returns null', async () => {
    const client = fakeAuthClient({ whoamiSeq: [null] });
    render(
      <AuthProvider authClient={client} getPseudonym={() => 'Renard 423'}>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('anon'));
  });

  it('renders status=authed when whoami returns a user', async () => {
    const client = fakeAuthClient({
      whoamiSeq: [{ userId: USER_ID, displayName: 'Lapin 472' }],
    });
    render(
      <AuthProvider authClient={client} getPseudonym={() => 'Renard 423'}>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('authed'));
    expect(screen.getByTestId('display').textContent).toBe('Lapin 472');
  });

  it('treats network errors as anon', async () => {
    const client = fakeAuthClient({ whoamiSeq: [new Error('fetch failed')] });
    render(
      <AuthProvider authClient={client} getPseudonym={() => 'Renard 423'}>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('status').textContent).toBe('anon'));
  });

  it('carries the anon pseudonym over on first sign-in when displayName=Joueur and local is a default', async () => {
    const client = fakeAuthClient({
      whoamiSeq: [
        { userId: USER_ID, displayName: 'Joueur' },
        { userId: USER_ID, displayName: 'Renard 423' },
      ],
    });
    render(
      <AuthProvider authClient={client} getPseudonym={() => 'Renard 423'}>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('display')?.textContent).toBe('Renard 423'));
    expect(client._calls.updateMe).toEqual(['Renard 423']);
  });

  it('does not carry over when the local pseudonym is custom', async () => {
    const client = fakeAuthClient({
      whoamiSeq: [{ userId: USER_ID, displayName: 'Joueur' }],
    });
    render(
      <AuthProvider authClient={client} getPseudonym={() => 'MonNom'}>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('display')?.textContent).toBe('Joueur'));
    expect(client._calls.updateMe).toEqual([]);
  });

  it('only attempts the carry-over PATCH once across multiple refreshes', async () => {
    const client = fakeAuthClient({
      whoamiSeq: [
        { userId: USER_ID, displayName: 'Joueur' },
        { userId: USER_ID, displayName: 'Renard 423' },
        { userId: USER_ID, displayName: 'Renard 423' },
      ],
    });
    render(
      <AuthProvider authClient={client} getPseudonym={() => 'Renard 423'}>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('display')?.textContent).toBe('Renard 423'));
    // Simulate a tab-focus visibility change — should re-check but never re-PATCH.
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await waitFor(() => expect(client._calls.whoami).toBeGreaterThanOrEqual(3));
    expect(client._calls.updateMe).toEqual(['Renard 423']);
  });

  it('settles authed even when the carry-over PATCH fails', async () => {
    const client = fakeAuthClient({
      whoamiSeq: [{ userId: USER_ID, displayName: 'Joueur' }],
      updateMeImpl: async () => { throw new Error('400'); },
    });
    render(
      <AuthProvider authClient={client} getPseudonym={() => 'Renard 423'}>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('display')?.textContent).toBe('Joueur'));
  });

  it('useAuth throws when used outside the provider', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => render(<Probe />)).toThrow(/useAuth must be used inside/);
    spy.mockRestore();
  });
});
