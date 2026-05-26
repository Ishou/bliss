// `/sondage` — lazy half. Loads the rating loop on demand. Auth is
// optional: anon visitors see the page, the sign-in banner invites
// them, and the correctif slot is hidden in anon mode. Anon rated
// itemIds are tracked in localStorage (survey.anon.rated_ids) and
// passed to /v1/items/next as `excluded` for client-side dedup.

import { createLazyRoute } from '@tanstack/react-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { css } from 'styled-system/css';
import { NOOP_ANALYTICS } from '@/application/analytics';
import type { RatingSubmission, SurveyItem } from '@/application/survey';
import { useAuth } from '@/ui/components/auth';
import { ContentPage } from '@/ui/components/layout';
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

function SondagePage() {
  const ctx = ParentRoute.useRouteContext();
  const { state } = useAuth();
  const isAuth = state.status === 'authed';
  const surveyClient = ctx.surveyClient;
  const analytics = ctx.analytics ?? NOOP_ANALYTICS;
  const authClient = ctx.authClient;

  const [item, setItem] = useState<SurveyItem | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const sessionStartedRef = useRef(false);

  const loadAnonRated = useCallback((): readonly string[] => {
    // Read straight from localStorage so the route doesn't import the
    // infrastructure adapter directly. The key is a stable spec constant.
    try {
      const raw = localStorage.getItem('survey.anon.rated_ids');
      if (!raw) return [];
      const parsed = JSON.parse(raw) as unknown;
      return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string') : [];
    } catch {
      return [];
    }
  }, []);

  const addAnonRated = useCallback((id: string): void => {
    try {
      const current = loadAnonRated();
      if (current.includes(id)) return;
      const next = [...current, id];
      while (next.length > 500) next.shift();
      localStorage.setItem('survey.anon.rated_ids', JSON.stringify(next));
    } catch {
      // localStorage may be unavailable; anon dedup is best-effort.
    }
  }, [loadAnonRated]);

  const loadNext = useCallback(async (): Promise<void> => {
    if (!surveyClient) {
      setLoading(false);
      setError('Le sondage n’est pas disponible pour le moment.');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const excludedItemIds = isAuth ? undefined : loadAnonRated();
      const next = await surveyClient.getNextItem({ excludedItemIds });
      setItem(next);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setLoading(false);
    }
  }, [surveyClient, isAuth, loadAnonRated]);

  // Session-start event fires once per page visit (idempotent across
  // auth-state flips so a sign-in mid-session doesn't double-count).
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

  async function onSubmit(payload: RatingSubmission): Promise<void> {
    if (!surveyClient || !item) return;
    try {
      const result = await surveyClient.submitRating(item.itemId, payload);
      analytics.trackEvent(
        'survey',
        'rating_submitted',
        `tier=${item.tier};submitted_as=${result.submittedAs}`,
      );
      if (payload.correctif) {
        analytics.trackEvent('survey', 'correctif_proposed', undefined);
      }
      if (payload.flag) {
        analytics.trackEvent('survey', 'flag_raised', payload.flag);
      }
      if (!isAuth) addAnonRated(item.itemId);
      await loadNext();
    } catch (cause) {
      const name = (cause as Error | undefined)?.name ?? '';
      if (name === 'SignInRequiredError') {
        analytics.trackEvent('survey', 'signin_prompt_shown', 'correctif_anon');
        setError('Connectez-vous pour proposer une correction.');
        return;
      }
      if (name === 'CorrectifRejectedError') {
        const detail = (cause as { detail?: { filterId?: number; reason?: string } }).detail;
        setError(
          `Correction rejetée par le filtre ${detail?.filterId ?? '?'} : ${detail?.reason ?? 'motif inconnu'}.`,
        );
        return;
      }
      if (name === 'AlreadyRatedError') {
        // The auth caller has already rated this item; the server replied with
        // the existing rating envelope. Skip ahead to the next item.
        if (!isAuth) addAnonRated(item.itemId);
        await loadNext();
        return;
      }
      setError(cause instanceof Error ? cause.message : String(cause));
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
          Notez la qualité et la difficulté de chaque définition. Vos retours
          alimentent la sélection des indices.
        </p>

        {!isAuth && authClient ? (
          <SignInBanner authClient={authClient} onClick={onSignInClick} />
        ) : null}

        {loading ? (
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
          <RatingCard
            key={item.itemId}
            item={item}
            isAuthenticated={isAuth}
            onSubmit={onSubmit}
          />
        ) : null}
      </article>
    </ContentPage>
  );
}

export const Route = createLazyRoute('/sondage')({
  component: SondagePage,
});
