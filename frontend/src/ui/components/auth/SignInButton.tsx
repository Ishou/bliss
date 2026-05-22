import { useEffect, useState } from 'react';
import { css } from 'styled-system/css';
import type { AuthClient } from '@/application/auth';

// Real anchor — Phase 5 §User flows. A `<button>` + `window.location.assign`
// loses the navigation semantics the browser needs to follow the 302 chain
// and accept the Set-Cookie at the identity-api callback.
const signInLinkStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  paddingInline: 'sm',
  paddingBlock: '4px',
  fontFamily: 'body',
  fontSize: 'sm',
  fontWeight: 'medium',
  color: 'fg',
  textDecoration: 'none',
  border: '1px solid token(colors.gridLine)',
  borderRadius: 'md',
  bg: 'surface',
  transition: 'background-color 120ms ease-out, border-color 120ms ease-out',
  _hover: { borderColor: 'accent' },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
});

export interface SignInButtonProps {
  readonly authClient: AuthClient;
}

// returnTo is computed post-hydration so the prerender HTML doesn't bake the local preview URL.
export function SignInButton({ authClient }: SignInButtonProps) {
  const [returnTo, setReturnTo] = useState<string>('');
  useEffect(() => {
    setReturnTo(window.location.href);
  }, []);
  const href = returnTo ? authClient.signInUrl('google', returnTo) : '#';
  return (
    <a
      className={signInLinkStyles}
      href={href}
      aria-disabled={returnTo ? undefined : true}
    >
      Se connecter
    </a>
  );
}
