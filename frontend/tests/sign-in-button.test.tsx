import { render, screen } from '@testing-library/react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { AuthClient } from '@/application/auth';
import { SignInButton } from '@/ui/components/auth';

function fakeAuthClient(): AuthClient {
  return {
    async whoami() { return null; },
    async getMe() { throw new Error('not used'); },
    async updateMe() {},
    async deleteMe() {},
    async logout() {},
    signInUrl(provider, returnTo) {
      return `https://auth.test/v1/auth/${provider}/login?return_to=${encodeURIComponent(returnTo)}`;
    },
  };
}

describe('SignInButton', () => {
  // Mirrors the prerender path: HTML produced before useEffect fires.
  it('renders a safe placeholder href before hydration', () => {
    const markup = renderToStaticMarkup(<SignInButton authClient={fakeAuthClient()} />);
    expect(markup).toContain('href="#"');
    expect(markup).toContain('aria-disabled="true"');
    expect(markup).not.toContain('return_to=');
  });

  it('updates href to include return_to after hydration', () => {
    render(<SignInButton authClient={fakeAuthClient()} />);
    const link = screen.getByRole('link', { name: 'Se connecter' });
    const href = link.getAttribute('href') ?? '';
    expect(href).toContain('return_to=');
    expect(href).toContain(encodeURIComponent(window.location.href));
    expect(link.getAttribute('aria-disabled')).toBeNull();
  });
});
