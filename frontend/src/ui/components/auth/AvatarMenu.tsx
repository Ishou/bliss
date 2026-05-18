import { Popover } from '@ark-ui/react/popover';
import { Portal } from '@ark-ui/react/portal';
import { Link, useNavigate } from '@tanstack/react-router';
import { useState } from 'react';
import { css } from 'styled-system/css';
import type { AuthClient, WhoAmIResult } from '@/application/auth';
import { useAuth } from './AuthProvider';

// Phase 5 sub-PR 4 — avatar popover (Ark UI). Trigger is the initial.

const triggerStyles = css({
  display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
  width: { base: '32px', md: '36px' }, height: { base: '32px', md: '36px' },
  borderRadius: 'full', bg: 'surface', border: '1px solid token(colors.border)',
  color: 'fg', fontFamily: 'body', fontSize: 'sm', fontWeight: 'semibold', cursor: 'pointer',
  transition: 'background-color 120ms ease-out, border-color 120ms ease-out',
  _hover: { borderColor: 'accent' },
  _focusVisible: { outline: '2px solid token(colors.focusRing)', outlineOffset: '2px' },
});

const positionerStyles = css({ zIndex: 1500 });

const contentStyles = css({
  position: 'relative', zIndex: 1500, bg: 'surface', color: 'fg',
  border: '1px solid token(colors.border)', borderRadius: 'md', padding: 'sm',
  minWidth: '220px', boxShadow: '0 12px 32px -10px rgba(0, 0, 0, 0.45)',
  display: 'flex', flexDirection: 'column', gap: 'xs',
  fontFamily: 'body', fontSize: 'sm', outline: 'none',
  _focusVisible: { outline: '2px solid token(colors.focusRing)', outlineOffset: '2px' },
});

// CSS-only truncation — JS slicing would mangle grapheme clusters.
const displayNameStyles = css({
  display: 'block', maxWidth: '180px', overflow: 'hidden', textOverflow: 'ellipsis',
  whiteSpace: 'nowrap', paddingInline: 'xs', paddingBlock: '4px',
  fontWeight: 'semibold', color: 'fg',
});

const itemBaseStyles = css({
  display: 'flex', alignItems: 'center', gap: '8px',
  paddingInline: 'xs', paddingBlock: '8px', borderRadius: 'sm',
  color: 'fg', fontFamily: 'body', fontSize: 'sm',
  bg: 'transparent', border: 'none', cursor: 'pointer',
  textDecoration: 'none', textAlign: 'start',
  transition: 'background-color 100ms ease-out, color 100ms ease-out',
  _hover: {
    bg: 'color-mix(in srgb, token(colors.accent) 14%, transparent)', color: 'accent',
  },
  _focusVisible: { outline: '2px solid token(colors.focusRing)', outlineOffset: '2px' },
});

function initialFor(displayName: string): string {
  // Iterator handles surrogate pairs / grapheme clusters.
  const first = [...displayName][0];
  return (first ?? '?').toLocaleUpperCase('fr-FR');
}

export interface AvatarMenuProps {
  readonly authClient: AuthClient;
  readonly whoami: WhoAmIResult;
  /**
   * Optional pre-logout hook wired to `lobbyClient.unbindLobbySessions` so
   * authed lobby seats revert to the anon pseudonym before the session cookie
   * is cleared. Failures are swallowed locally and logged — logout proceeds
   * regardless.
   */
  readonly onBeforeLogout?: () => Promise<void>;
}

export function AvatarMenu({ authClient, whoami, onBeforeLogout }: AvatarMenuProps) {
  const { refresh } = useAuth();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);

  const handleLogout = async () => {
    if (onBeforeLogout) {
      try {
        await onBeforeLogout();
      } catch (cause) {
        console.warn('lobby unbind failed; logging out anyway', cause);
      }
    }
    try {
      await authClient.logout();
      setOpen(false);
      await refresh();
      void navigate({ to: '/' });
    } catch {
      setOpen(false);
    }
  };

  return (
    <Popover.Root
      open={open}
      onOpenChange={(details) => setOpen(details.open)}
      positioning={{ placement: 'bottom-end', gutter: 6 }}
    >
      <Popover.Trigger
        type="button"
        aria-label="Compte"
        className={triggerStyles}
      >
        {initialFor(whoami.displayName)}
      </Popover.Trigger>
      <Portal>
        <Popover.Positioner className={positionerStyles}>
          <Popover.Content className={contentStyles}>
            <span className={displayNameStyles} title={whoami.displayName}>
              {whoami.displayName}
            </span>
            <Link
              to="/compte"
              className={itemBaseStyles}
              onClick={() => setOpen(false)}
            >
              Mon compte
            </Link>
            <button
              type="button"
              className={itemBaseStyles}
              onClick={() => { void handleLogout(); }}
            >
              Se déconnecter
            </button>
          </Popover.Content>
        </Popover.Positioner>
      </Portal>
    </Popover.Root>
  );
}
