// `/sondage/pairs` lazy half — pairwise rating loop. Auth optional; anon dedup via surveyAnonStore.

import { createLazyRoute, Link } from '@tanstack/react-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { css } from 'styled-system/css';
import { NOOP_ANALYTICS } from '@/application/analytics';
import { messageForApiError } from '@/application/errors';
import type {
  ItemPair,
  LikertScore,
  PairRatingSubmission,
  PairVerdict,
} from '@/application/survey';
import { useAuth } from '@/ui/components/auth';
import { ContentPage } from '@/ui/components/layout';
import { LockBanner, PairCard, SignInBanner, useCampaignStatus } from '@/ui/components/sondage';
import { Route as ParentRoute } from './sondage.pairs';

const articleStyles = css({
  display: 'flex',
  flexDirection: 'column',
  gap: 'lg',
  width: '100%',
  maxWidth: '720px',
});

const headerRowStyles = css({
  display: 'flex',
  flexWrap: 'wrap',
  gap: 'sm',
  alignItems: 'baseline',
  justifyContent: 'space-between',
});

const headingStyles = css({
  fontSize: { base: 'xl', md: 'display' },
  fontWeight: 'bold',
  letterSpacing: '-0.02em',
  margin: 0,
  color: 'fg',
});

const introStyles = css({
  fontSize: 'body',
  color: 'fgMuted',
  margin: 0,
});

const statusStyles = css({
  fontSize: 'body',
  color: 'fgMuted',
  margin: 0,
});

const alertStyles = css({
  fontSize: 'body',
  color: 'errorText',
  margin: 0,
});

const modeLinkStyles = css({
  fontSize: 'sm',
  fontWeight: 'semibold',
  color: 'accent',
  textDecoration: 'underline',
  _hover: { opacity: 0.8 },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
    borderRadius: 'sm',
  },
});

// difficulte=3 placeholder mirrors `/sondage` (the binary route) until the schema allows nullable difficulté.
const DIFFICULTE_PLACEHOLDER: LikertScore = 3;

function SondagePairsPage() {
  const ctx = ParentRoute.useRouteContext();
  const { state } = useAuth();
  const isAuth = state.status === 'authed';
  const surveyClient = ctx.surveyClient;
  const surveyAnonStore = ctx.surveyAnonStore;
  const analytics = ctx.analytics ?? NOOP_ANALYTICS;
  const authClient = ctx.authClient;

  const campaignStatus = useCampaignStatus(surveyClient);
  const isLocked = campaignStatus.status.kind === 'closed';

  const [pair, setPair] = useState<ItemPair | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const sessionStartedRef = useRef(false);
  const authSkippedIdsRef = useRef<Set<string>>(new Set());

  const loadNext = useCallback(async (): Promise<void> => {
    if (!surveyClient) {
      setLoading(false);
      setError('Le sondage n’est pas disponible pour le moment.');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const anonSeen = surveyAnonStore?.list() ?? [];
      const excludedItemIds = isAuth
        ? [...anonSeen, ...Array.from(authSkippedIdsRef.current)]
        : anonSeen;
      const next = await surveyClient.getNextPair({ excludedItemIds });
      setPair(next);
    } catch (cause) {
      setError(messageForApiError(cause));
    } finally {
      setLoading(false);
    }
  }, [surveyClient, isAuth, surveyAnonStore]);

  // Idempotent: fires once per visit even if auth state flips mid-session.
  useEffect(() => {
    if (sessionStartedRef.current) return;
    if (state.status === 'loading') return;
    sessionStartedRef.current = true;
    analytics.trackEvent('survey', 'pair_session_start', isAuth ? 'auth' : 'anon');
  }, [state.status, isAuth, analytics]);

  useEffect(() => {
    if (state.status === 'loading') return;
    void loadNext();
  }, [state.status, loadNext]);

  const onVerdict = useCallback(async (verdict: PairVerdict, latencyMs: number): Promise<void> => {
    if (!surveyClient || !pair) return;
    const currentPair = pair;
    const leftItemId = currentPair.left.itemId;
    const rightItemId = currentPair.right.itemId;
    if (verdict === 'SKIP') {
      analytics.trackEvent('survey', 'pair_verdict_skipped', `tier=${currentPair.left.tier}`);
      if (isAuth) {
        authSkippedIdsRef.current.add(leftItemId);
        authSkippedIdsRef.current.add(rightItemId);
      } else {
        surveyAnonStore?.add(leftItemId);
        surveyAnonStore?.add(rightItemId);
      }
      await loadNext();
      return;
    }
    const payload: PairRatingSubmission = {
      leftItemId,
      rightItemId,
      verdict,
      difficulte: DIFFICULTE_PLACEHOLDER,
      latencyMs,
    };
    try {
      await surveyClient.submitPairRating(payload);
      analytics.trackEvent(
        'survey',
        'pair_verdict_submitted',
        `tier=${currentPair.left.tier};verdict=${verdict}`,
      );
      if (!isAuth) {
        surveyAnonStore?.add(leftItemId);
        surveyAnonStore?.add(rightItemId);
      }
      await loadNext();
    } catch (cause) {
      const name = (cause as Error | undefined)?.name ?? '';
      if (name === 'AlreadyRatedError') {
        if (!isAuth) {
          surveyAnonStore?.add(leftItemId);
          surveyAnonStore?.add(rightItemId);
        }
        await loadNext();
        return;
      }
      if (name === 'SondageLockedError') {
        campaignStatus.refresh();
        return;
      }
      setError(messageForApiError(cause));
    }
  }, [surveyClient, pair, isAuth, surveyAnonStore, analytics, loadNext, campaignStatus]);

  function onSignInClick(): void {
    analytics.trackEvent('survey', 'pair_signin_prompt_clicked', undefined);
  }

  return (
    <ContentPage>
      <article className={articleStyles}>
        <div className={headerRowStyles}>
          <h1 className={headingStyles}>Sondage par paires</h1>
          <Link to="/sondage" className={modeLinkStyles} data-testid="mode-switch-binary">
            Mode binaire →
          </Link>
        </div>
        {campaignStatus.status.kind === 'closed' ? (
          <LockBanner campaign={campaignStatus.status.campaign} />
        ) : null}
        <p className={introStyles}>
          Comparez deux définitions du même mot. Choisissez votre préférée, marquez-les comme
          toutes deux bonnes ou mauvaises, ou passez si vous ne pouvez pas trancher.
        </p>

        {state.status === 'anon' && authClient ? (
          <SignInBanner authClient={authClient} onClick={onSignInClick} />
        ) : null}

        {loading || state.status === 'loading' ? (
          <p className={statusStyles} role="status">Chargement…</p>
        ) : null}

        {error !== null ? (
          <p className={alertStyles} role="alert">{error}</p>
        ) : null}

        {!loading && pair === null && error === null ? (
          <p className={statusStyles}>
            Plus de paires à comparer pour l&apos;instant. Merci !
          </p>
        ) : null}

        {pair !== null ? (
          <PairCard
            key={`${pair.left.itemId}|${pair.right.itemId}`}
            pair={pair}
            onVerdict={onVerdict}
            disabled={isLocked}
          />
        ) : null}
      </article>
    </ContentPage>
  );
}

export const Route = createLazyRoute('/sondage/pairs')({
  component: SondagePairsPage,
});
