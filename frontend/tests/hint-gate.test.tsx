import { renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { ReactNode } from 'react';
import type { AuthClient, WhoAmIResult } from '@/application/auth';
import { AuthProvider, useHintGate } from '@/ui/components/auth';

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

function withAuth(client: AuthClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <AuthProvider authClient={client} getPseudonym={() => 'Renard 423'}>
        {children}
      </AuthProvider>
    );
  };
}

describe('useHintGate', () => {
  it('returns disabled gate props with sign-in tooltip when status=anon', async () => {
    const { result } = renderHook(
      () => useHintGate(),
      { wrapper: withAuth(makeClient({ whoami: null })) },
    );
    await waitFor(() =>
      expect(result.current).toMatchObject({
        disabled: true,
        'aria-disabled': true,
        title: 'Connectez-vous pour utiliser les indices.',
      }),
    );
  });

  it('returns disabled gate props with loading tooltip while status=loading', () => {
    const latch = new Promise<void>(() => {});
    const { result } = renderHook(
      () => useHintGate(),
      { wrapper: withAuth(makeClient({ whoami: null, latch })) },
    );
    expect(result.current).toMatchObject({
      disabled: true,
      'aria-disabled': true,
      title: 'Chargement…',
    });
  });

  it('returns null when status=authed', async () => {
    const { result } = renderHook(
      () => useHintGate(),
      { wrapper: withAuth(makeClient({ whoami: USER })) },
    );
    await waitFor(() => expect(result.current).toBeNull());
  });

  it('returns null when rendered outside an AuthProvider', () => {
    const { result } = renderHook(() => useHintGate());
    expect(result.current).toBeNull();
  });
});
