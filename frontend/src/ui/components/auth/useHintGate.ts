import { useOptionalAuth } from './AuthProvider';

const DISABLED_TOOLTIP_ANON = 'Connectez-vous pour utiliser les indices.';
const DISABLED_TOOLTIP_LOADING = 'Chargement…';

type GateProps = {
  readonly disabled: true;
  readonly 'aria-disabled': true;
  readonly title: string;
};

export function useHintGate(): GateProps | null {
  const auth = useOptionalAuth();
  if (!auth || auth.state.status === 'authed') return null;
  return {
    disabled: true,
    'aria-disabled': true,
    title: auth.state.status === 'loading' ? DISABLED_TOOLTIP_LOADING : DISABLED_TOOLTIP_ANON,
  };
}
