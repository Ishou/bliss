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

export function SignInButton({ authClient }: SignInButtonProps) {
  const returnTo = typeof window !== 'undefined' ? window.location.href : '';
  const href = authClient.signInUrl('google', returnTo);
  return (
    <a className={signInLinkStyles} href={href}>
      Se connecter
    </a>
  );
}
