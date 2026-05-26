// Persistent invitation banner shown to anonymous /sondage visitors.
// Clicking dispatches a Matomo event via the parent's onClick callback
// and lets the browser follow the OAuth href so the 302 chain accepts
// the Set-Cookie at the identity-api callback (same pattern as
// SignInButton).

import { useEffect, useState } from 'react';
import { css } from 'styled-system/css';
import type { AuthClient } from '@/application/auth';

const bannerStyles = css({
  display: 'flex',
  flexDirection: { base: 'column', md: 'row' },
  alignItems: { base: 'flex-start', md: 'center' },
  gap: 'sm',
  paddingBlock: 'sm',
  paddingInline: 'md',
  bg: 'surface',
  border: '1px solid token(colors.border)',
  borderRadius: 'md',
  color: 'fg',
});

const textStyles = css({
  fontSize: 'body',
  flex: 1,
});

const ctaStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  paddingInline: 'sm',
  paddingBlock: '6px',
  fontFamily: 'body',
  fontSize: 'sm',
  fontWeight: 'semibold',
  color: 'onAccent',
  bg: 'accent',
  border: 'none',
  borderRadius: '6px',
  textDecoration: 'none',
  cursor: 'pointer',
  _hover: { bg: 'primary.400' },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
  '&[aria-disabled="true"]': { opacity: 0.6, cursor: 'not-allowed' },
});

export interface SignInBannerProps {
  readonly authClient: AuthClient;
  readonly onClick: () => void;
}

export function SignInBanner({ authClient, onClick }: SignInBannerProps) {
  // returnTo is computed post-hydration so prerender HTML doesn't bake
  // a preview URL (mirrors SignInButton).
  const [returnTo, setReturnTo] = useState('');
  useEffect(() => {
    setReturnTo(window.location.href);
  }, []);
  const href = returnTo ? authClient.signInUrl('google', returnTo) : '#';

  return (
    <aside className={bannerStyles} role="note" aria-label="Invitation à se connecter">
      <p className={textStyles}>
        Connectez-vous pour proposer vos propres indices et suivre vos contributions.
      </p>
      <a
        className={ctaStyles}
        href={href}
        aria-disabled={returnTo ? undefined : true}
        onClick={onClick}
      >
        Se connecter
      </a>
    </aside>
  );
}
