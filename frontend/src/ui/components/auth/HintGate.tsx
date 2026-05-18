import { Children, cloneElement, isValidElement, type ReactElement, type ReactNode } from 'react';
import { useOptionalAuth } from './AuthProvider';

// Sign-in gate for the per-puzzle hint affordance. When the user is not
// authed (or the auth state is still loading), the wrapped button is
// forced disabled + given a tooltip. ADR-0045 §Hint-gate: a native
// `<button disabled>` suppresses click events entirely, so no redirect
// or dialog needs to fire on click — the avatar in the header is the
// only sign-in discovery surface.

const DISABLED_TOOLTIP_ANON = 'Connectez-vous pour utiliser les indices.';
const DISABLED_TOOLTIP_LOADING = 'Chargement…';

type GatedProps = {
  readonly disabled?: boolean;
  readonly title?: string;
  readonly 'aria-disabled'?: boolean | 'true' | 'false';
};

export interface HintGateProps {
  readonly children: ReactNode;
}

export function HintGate({ children }: HintGateProps) {
  const auth = useOptionalAuth();
  // Outside an AuthProvider (route-level test fixtures that don't wire
  // auth, server-side prerender of the head) — degrade to a pass-through
  // so existing render paths stay green. Production renders below
  // `<AuthProvider>` so the gate is always active there.
  if (!auth || auth.state.status === 'authed') return <>{children}</>;

  const tooltip = auth.state.status === 'loading' ? DISABLED_TOOLTIP_LOADING : DISABLED_TOOLTIP_ANON;

  return (
    <>
      {Children.map(children, (child) => {
        if (!isValidElement(child)) return child;
        // Override `disabled` + `title` + `aria-disabled` on the wrapped
        // element. The existing `disabled` prop (e.g. hintsRemaining===0)
        // is replaced — that's intentional: anon also disables, and the
        // user-visible end state is the same.
        return cloneElement(child as ReactElement<GatedProps>, {
          disabled: true,
          'aria-disabled': true,
          title: tooltip,
        });
      })}
    </>
  );
}
