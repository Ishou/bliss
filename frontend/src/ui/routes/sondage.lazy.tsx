// `/sondage` lazy half — rating loop. Auth optional; anon dedup via surveyAnonStore.

import { createLazyRoute } from '@tanstack/react-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { css } from 'styled-system/css';
import { NOOP_ANALYTICS } from '@/application/analytics';
import { messageForApiError } from '@/application/errors';
import type { LikertScore, RatingSubmission, SurveyItem } from '@/application/survey';
import { useAuth } from '@/ui/components/auth';
import { ContentPage } from '@/ui/components/layout';
import type { Verdict } from '@/ui/components/sondage';
import { RatingCard, SignInBanner } from '@/ui/components/sondage';
import { Route as ParentRoute } from './sondage';

const articleStyles = css({
  display: 'flex',
  flexDirection: 'column',
  gap: 'lg',
  width: '100%',
  maxWidth: '720px',
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

// difficulte=3 placeholder per docs/superpowers/plans/2026-05-26-sondage-simplification.md scope-honesty.
const DIFFICULTE_PLACEHOLDER: LikertScore = 3;

function SondagePage() {
  const ctx = ParentRoute.useRouteContext();
  const { state } = useAuth();
  const isAuth = state.status === 'authed';
  const surveyClient = ctx.surveyClient;
  const surveyAnonStore = ctx.surveyAnonStore;
  const analytics = ctx.analytics ?? NOOP_ANALYTICS;
  const authClient = ctx.authClient;

  const [item, setItem] = useState<SurveyItem | null>(null);
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
      const excludedItemIds = isAuth
        ? Array.from(authSkippedIdsRef.current)
        : surveyAnonStore?.list();
      const next = await surveyClient.getNextItem({ excludedItemIds });
      setItem(next);
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
    analytics.trackEvent('survey', 'session_start', isAuth ? 'auth' : 'anon');
  }, [state.status, isAuth, analytics]);

  useEffect(() => {
    if (state.status === 'loading') return;
    void loadNext();
  }, [state.status, loadNext]);

  async function onVerdict(verdict: Verdict, latencyMs: number): Promise<void> {
    if (!surveyClient || !item) return;
    const currentItem = item;
    if (verdict === 'SKIP') {
      analytics.trackEvent('survey', 'verdict_skipped', `tier=${currentItem.tier}`);
      if (isAuth) {
        authSkippedIdsRef.current.add(currentItem.itemId);
      } else {
        surveyAnonStore?.add(currentItem.itemId);
      }
      await loadNext();
      return;
    }
    const payload: RatingSubmission = {
      qualite: verdict === 'GOOD' ? 5 : 1,
      difficulte: DIFFICULTE_PLACEHOLDER,
      latencyMs,
    };
    try {
      await surveyClient.submitRating(currentItem.itemId, payload);
      analytics.trackEvent(
        'survey',
        'verdict_submitted',
        `tier=${currentItem.tier};verdict=${verdict}`,
      );
      if (!isAuth) surveyAnonStore?.add(currentItem.itemId);
      await loadNext();
    } catch (cause) {
      const name = (cause as Error | undefined)?.name ?? '';
      if (name === 'AlreadyRatedError') {
        if (!isAuth) surveyAnonStore?.add(currentItem.itemId);
        await loadNext();
        return;
      }
      setError(messageForApiError(cause));
    }
  }

  function onSignInClick(): void {
    analytics.trackEvent('survey', 'signin_prompt_clicked', undefined);
  }

  return (
    <ContentPage>
      <article className={articleStyles}>
        <h1 className={headingStyles}>Sondage des indices</h1>
        <p className={introStyles}>
          Notez la qualité des définitions en un clic : mauvaise, à passer, ou bonne.
          Vos retours alimentent la sélection des indices.
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

        {!loading && item === null && error === null ? (
          <p className={statusStyles}>
            Plus d&apos;indices à noter pour l&apos;instant. Merci !
          </p>
        ) : null}

        {item !== null ? (
          <RatingCard key={item.itemId} item={item} onVerdict={onVerdict} />
        ) : null}
      </article>
    </ContentPage>
  );
}

export const Route = createLazyRoute('/sondage')({
  component: SondagePage,
});
