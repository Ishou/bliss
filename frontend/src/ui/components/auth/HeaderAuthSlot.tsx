import { css } from 'styled-system/css';
import type { AuthClient } from '@/application/auth';
import { AvatarMenu } from './AvatarMenu';
import { SignInButton } from './SignInButton';
import { useAuth } from './AuthProvider';

// Visual placeholder while AuthProvider is resolving whoami. Not informative
// to screen readers (aria-hidden) — the header's purpose is unchanged.
const skeletonStyles = css({
  display: 'inline-block',
  width: '28px',
  height: '28px',
  borderRadius: 'full',
  bg: 'surface',
  opacity: 0.5,
});

export interface HeaderAuthSlotProps {
  readonly authClient: AuthClient;
  readonly onBeforeLogout?: () => Promise<void>;
}

export function HeaderAuthSlot({ authClient, onBeforeLogout }: HeaderAuthSlotProps) {
  const { state } = useAuth();
  if (state.status === 'loading') {
    return <span className={skeletonStyles} aria-hidden="true" />;
  }
  if (state.status === 'anon') {
    return <SignInButton authClient={authClient} />;
  }
  return <AvatarMenu authClient={authClient} whoami={state.whoami} onBeforeLogout={onBeforeLogout} />;
}
