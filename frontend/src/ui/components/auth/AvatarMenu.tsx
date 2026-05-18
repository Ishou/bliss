import { useState } from 'react';
import { css } from 'styled-system/css';
import type { AuthClient, WhoAmIResult } from '@/application/auth';

// Stub Avatar surface for sub-PR 3 — full Ark UI popover lands in
// Task 4 (AvatarMenu + /compte route foundation). For now: a labelled
// initial + an inline "Se déconnecter" button so the authed state has
// a working sign-out path immediately after this PR ships.
const avatarStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  gap: 'xs',
});

const avatarChipStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  width: '28px',
  height: '28px',
  borderRadius: 'full',
  bg: 'accent',
  color: 'bg',
  fontFamily: 'body',
  fontSize: 'xs',
  fontWeight: 'bold',
  userSelect: 'none',
});

const signOutButtonStyles = css({
  fontFamily: 'body',
  fontSize: 'xs',
  fontWeight: 'medium',
  color: 'fgMuted',
  bg: 'transparent',
  border: 'none',
  cursor: 'pointer',
  paddingInline: '4px',
  _hover: { color: 'fg' },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
    borderRadius: '2px',
  },
});

export interface AvatarMenuProps {
  readonly authClient: AuthClient;
  readonly whoami: WhoAmIResult;
  readonly onSignedOut: () => void;
}

export function AvatarMenu({ authClient, whoami, onSignedOut }: AvatarMenuProps) {
  const [busy, setBusy] = useState(false);
  const initial = whoami.displayName.trim().charAt(0).toUpperCase() || '?';
  const handleSignOut = async () => {
    if (busy) return;
    setBusy(true);
    try {
      await authClient.logout();
    } finally {
      setBusy(false);
      onSignedOut();
    }
  };
  return (
    <span className={avatarStyles}>
      <span
        className={avatarChipStyles}
        role="img"
        aria-label={`Compte de ${whoami.displayName}`}
      >
        {initial}
      </span>
      <button
        type="button"
        className={signOutButtonStyles}
        onClick={() => {
          void handleSignOut();
        }}
        disabled={busy}
      >
        Se déconnecter
      </button>
    </span>
  );
}
