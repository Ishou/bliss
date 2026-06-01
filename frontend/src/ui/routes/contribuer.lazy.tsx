// `/contribuer` lazy half — rating loop. Auth optional; anon dedup via surveyAnonStore.

import { createLazyRoute, Link } from '@tanstack/react-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { css } from 'styled-system/css';
import { NOOP_ANALYTICS } from '@/application/analytics';
import { messageForApiError } from '@/application/errors';
import { campaignDisplayName } from '@/application/survey';
import type { LikertScore, RatingSubmission, SurveyItem, SurveyPos } from '@/application/survey';
import { useAuth } from '@/ui/components/auth';
import { ContentPage } from '@/ui/components/layout';
import type { Verdict } from '@/ui/components/sondage';
import { LockBanner, RatingCard, SignInBanner, UndoBar, useCampaignStatus } from '@/ui/components/sondage';
import { Route as ParentRoute } from './contribuer';

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

const subtitleStyles = css({
  fontSize: 'sm',
  color: 'fgMuted',
  margin: 0,
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

// difficulte=3 placeholder pending schema nullable migration; qualite=5 drives RAFT winners so no algorithmic impact.
const DIFFICULTE_PLACEHOLDER: LikertScore = 3;

function ContribuerPage() {
  const ctx = ParentRoute.useRouteContext();
  const { state } = useAuth();
  const isAuth = state.status === 'authed';
  const surveyClient = ctx.surveyClient;
  const surveyAnonStore = ctx.surveyAnonStore;
  const analytics = ctx.analytics ?? NOOP_ANALYTICS;
  const authClient = ctx.authClient;

  const campaignStatus = useCampaignStatus(surveyClient);
  const isLocked =
    campaignStatus.status.kind === 'closed' || campaignStatus.status.kind === 'unavailable';

  const [item, setItem] = useState<SurveyItem | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [lastAction, setLastAction] = useState<{ token: string; item: SurveyItem } | null>(null);
  const [undoBusy, setUndoBusy] = useState(false);
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
      // anon store also excluded when auth: server dedups on user_id, so pre-auth ratings (stored with user_id=NULL) wouldn't otherwise be filtered.
      const anonSeen = surveyAnonStore?.list() ?? [];
      const excludedItemIds = isAuth
        ? [...anonSeen, ...Array.from(authSkippedIdsRef.current)]
        : anonSeen;
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

  async function onVerdict(
    verdict: Verdict,
    latencyMs: number,
    meta: { readonly targetSenses: ReadonlyArray<string> },
  ): Promise<void> {
    if (!surveyClient || !item) return;
    const currentItem = item;
    if (verdict === 'SKIP') {
      analytics.trackEvent('survey', 'verdict_skipped', `tier=${currentItem.tier}`);
      if (isAuth) {
        authSkippedIdsRef.current.add(currentItem.itemId);
      } else {
        surveyAnonStore?.add(currentItem.itemId);
      }
      setLastAction(null);
      await loadNext();
      return;
    }
    const senses = isAuth && meta.targetSenses.length > 0 ? [...meta.targetSenses] : undefined;
    const payload: RatingSubmission = {
      qualite: verdict === 'GOOD' ? 5 : 1,
      difficulte: DIFFICULTE_PLACEHOLDER,
      latencyMs,
      ...(senses ? { targetSenses: senses } : {}),
    };
    try {
      const result = await surveyClient.submitRating(currentItem.itemId, payload);
      analytics.trackEvent(
        'survey',
        'verdict_submitted',
        `tier=${currentItem.tier};verdict=${verdict}`,
      );
      if (!isAuth) surveyAnonStore?.add(currentItem.itemId);
      if (result.undoToken) setLastAction({ token: result.undoToken, item: currentItem });
      await loadNext();
    } catch (cause) {
      const name = (cause as Error | undefined)?.name ?? '';
      if (name === 'AlreadyRatedError') {
        if (!isAuth) surveyAnonStore?.add(currentItem.itemId);
        await loadNext();
        return;
      }
      if (name === 'SondageLockedError') {
        campaignStatus.refresh();
        return;
      }
      setError(messageForApiError(cause));
    }
  }

  async function onCorriger(
    correctedText: string,
    pos: SurveyPos,
    latencyMs: number,
    meta: { readonly targetSenses: ReadonlyArray<string> },
  ): Promise<void> {
    if (!surveyClient || !item) return;
    if (!isAuth) {
      setError('Connectez-vous pour proposer une correction.');
      return;
    }
    const currentItem = item;
    const senses = meta.targetSenses.length > 0 ? [...meta.targetSenses] : undefined;
    // qualite=3 stays neutral on the original; the server patches POS in place or creates an auto-GOOD rater_proposed item per ADR-0056.
    const payload: RatingSubmission = {
      qualite: 3,
      difficulte: DIFFICULTE_PLACEHOLDER,
      latencyMs,
      correctif: { text: correctedText, style: currentItem.style, pos },
      ...(senses ? { targetSenses: senses } : {}),
    };
    try {
      const result = await surveyClient.submitRating(currentItem.itemId, payload);
      analytics.trackEvent('survey', 'correctif_proposed', `tier=${currentItem.tier}`);
      if (result.undoToken) setLastAction({ token: result.undoToken, item: currentItem });
      await loadNext();
    } catch (cause) {
      const name = (cause as Error | undefined)?.name ?? '';
      if (name === 'CorrectifRejectedError') {
        const detail = (cause as { detail?: { filterId?: number; reason?: string } }).detail;
        setError(
          `Correction rejetée par le filtre ${detail?.filterId ?? '?'} : ${detail?.reason ?? 'motif inconnu'}.`,
        );
        return;
      }
      if (name === 'SondageLockedError') {
        campaignStatus.refresh();
        return;
      }
      setError(messageForApiError(cause));
    }
  }

  async function onUndo(): Promise<void> {
    if (!surveyClient || !lastAction) return;
    const { token, item: stashedItem } = lastAction;
    setUndoBusy(true);
    try {
      await surveyClient.undoAction(token);
      analytics.trackEvent('survey', 'verdict_undone', undefined);
      if (!isAuth) surveyAnonStore?.remove(stashedItem.itemId);
      authSkippedIdsRef.current.delete(stashedItem.itemId);
      setLastAction(null);
      setError(null);
      setItem(stashedItem);
    } catch (cause) {
      const name = (cause as Error | undefined)?.name ?? '';
      setLastAction(null);
      if (name === 'UndoExpiredError') {
        setError('Trop tard pour annuler : la campagne est terminée.');
      } else if (name === 'UndoUnavailableError') {
        setError('Cette action ne peut plus être annulée.');
      } else {
        setError(messageForApiError(cause));
      }
    } finally {
      setUndoBusy(false);
    }
  }

  function onSignInClick(): void {
    analytics.trackEvent('survey', 'signin_prompt_clicked', undefined);
  }

  return (
    <ContentPage>
      <article className={articleStyles}>
        <div className={headerRowStyles}>
          <h1 className={headingStyles}>Campagne des indices</h1>
          <Link to="/contribuer/pairs" className={modeLinkStyles} data-testid="mode-switch-pairs">
            Mode paires →
          </Link>
        </div>
        {campaignStatus.status.kind === 'open' ? (
          <p className={subtitleStyles} data-testid="campaign-subtitle">
            {campaignDisplayName(campaignStatus.status.campaign)}
          </p>
        ) : null}
        {campaignStatus.status.kind === 'closed' ? (
          <LockBanner campaign={campaignStatus.status.campaign} />
        ) : null}
        {campaignStatus.status.kind === 'unavailable' ? (
          <LockBanner campaign={null} />
        ) : null}
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

        {item !== null && !isLocked ? (
          <RatingCard
            key={item.itemId}
            item={item}
            onVerdict={onVerdict}
            onCorriger={onCorriger}
            surveyClient={surveyClient}
          />
        ) : null}

        {lastAction !== null ? <UndoBar onUndo={onUndo} busy={undoBusy} /> : null}
      </article>
    </ContentPage>
  );
}

export const Route = createLazyRoute('/contribuer')({
  component: ContribuerPage,
});
