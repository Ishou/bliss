// /compte section listing the caller's proposed corrections with their
// current K-coverage. Reads from the SurveyClient passed via props so
// the component remains pure and easily testable.

import { useEffect, useState } from 'react';
import { css } from 'styled-system/css';
import type { SurveyClient, SurveyContribution } from '@/application/survey';

const listStyles = css({
  listStyle: 'none',
  margin: 0,
  padding: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 'sm',
});

const itemStyles = css({
  paddingBlock: 'xs',
  borderBottom: '1px solid token(colors.border)',
  _last: { borderBottom: 'none' },
  fontSize: 'body',
  color: 'fg',
});

const statusStyles = css({
  fontSize: 'body',
  color: 'fgMuted',
  margin: 0,
});

const optedOutStyles = css({
  fontSize: 'sm',
  color: 'fgMuted',
  fontStyle: 'italic',
});

export interface MyContributionsProps {
  readonly surveyClient: SurveyClient;
}

export function MyContributions({ surveyClient }: MyContributionsProps) {
  const [items, setItems] = useState<ReadonlyArray<SurveyContribution> | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    surveyClient
      .getContributions()
      .then((list) => {
        if (!cancelled) setItems(list);
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setError(cause instanceof Error ? cause.message : String(cause));
        }
      });
    return () => { cancelled = true; };
  }, [surveyClient]);

  if (error !== null) {
    return <p className={statusStyles} role="alert">Impossible de charger vos contributions.</p>;
  }
  if (items === null) {
    return <p className={statusStyles} role="status">Chargement…</p>;
  }
  if (items.length === 0) {
    return <p className={statusStyles}>Vous n&apos;avez encore proposé aucune correction.</p>;
  }

  return (
    <ul className={listStyles}>
      {items.map((c) => (
        <li key={c.itemId} className={itemStyles}>
          <strong>{c.mot}</strong> — « {c.definition} » ({c.categorie}, {c.style})
          — couverture : {c.kCoverage}
          {c.optedOut ? (
            <>
              {' '}
              <em className={optedOutStyles}>
                (sera supprimée en cas de suppression du compte)
              </em>
            </>
          ) : null}
        </li>
      ))}
    </ul>
  );
}
