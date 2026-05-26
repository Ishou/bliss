// Rating card — composes Likert scales, FlagPicker, CorrectifField, and a submit button.

import { useEffect, useRef, useState } from 'react';
import { css } from 'styled-system/css';
import type {
  LikertScore,
  RatingSubmission,
  SurveyCorrectif,
  SurveyFlagReason,
  SurveyItem,
} from '@/application/survey';
import { Button } from '@/ui/components/primitives';
import { CorrectifField } from './CorrectifField';
import { FlagPicker } from './FlagPicker';
import { categorieLabel, posLabel, styleLabel } from './labels';
import { Likert } from './Likert';

const cardStyles = css({
  bg: 'surface',
  border: '1px solid token(colors.border)',
  borderRadius: 'md',
  padding: 'lg',
  display: 'flex',
  flexDirection: 'column',
  gap: 'md',
});

const titleStyles = css({
  fontSize: { base: 'xl', md: 'display' },
  letterSpacing: '-0.02em',
  margin: 0,
  color: 'fg',
});

const chipRowStyles = css({
  display: 'flex',
  flexWrap: 'wrap',
  gap: 'xs',
  margin: 0,
});

const chipStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  paddingInline: 'sm',
  paddingBlock: '4px',
  fontSize: 'xs',
  fontWeight: 'semibold',
  color: 'fgMuted',
  border: '1px solid token(colors.border)',
  borderRadius: 'sm',
});

const chipCategorieStyles = css({
  color: 'accent',
  borderColor: 'accent',
});

const definitionStyles = css({
  fontSize: 'body',
  fontStyle: 'italic',
  color: 'fg',
  margin: 0,
  paddingBlock: 'sm',
  paddingInline: 'md',
  borderLeft: '3px solid token(colors.accent)',
});

const metaStyles = css({
  fontSize: 'sm',
  color: 'fgMuted',
  margin: 0,
});

const submitRowStyles = css({
  display: 'flex',
  justifyContent: 'flex-end',
});

export interface RatingCardProps {
  readonly item: SurveyItem;
  readonly isAuthenticated: boolean;
  readonly onSubmit: (payload: RatingSubmission) => Promise<void> | void;
}

export function RatingCard({ item, isAuthenticated, onSubmit }: RatingCardProps) {
  const [qualite, setQualite] = useState<LikertScore | null>(null);
  const [difficulte, setDifficulte] = useState<LikertScore | null>(null);
  const [flag, setFlag] = useState<SurveyFlagReason | undefined>(undefined);
  const [correctif, setCorrectif] = useState<SurveyCorrectif | undefined>(undefined);
  const [submitting, setSubmitting] = useState(false);

  // Initialised in effect below — performance.now() in render trips react-hooks/purity.
  const startedAt = useRef<number>(0);

  useEffect(() => {
    startedAt.current = performance.now();
    setQualite(null);
    setDifficulte(null);
    setFlag(undefined);
    setCorrectif(undefined);
  }, [item.itemId]);

  async function submit(): Promise<void> {
    if (qualite === null || difficulte === null || submitting) return;
    setSubmitting(true);
    try {
      const latencyMs = Math.max(0, Math.round(performance.now() - startedAt.current));
      const payload: RatingSubmission = {
        qualite,
        difficulte,
        flag,
        correctif: isAuthenticated ? correctif : undefined,
        latencyMs,
      };
      await onSubmit(payload);
    } finally {
      setSubmitting(false);
    }
  }

  const canSubmit = qualite !== null && difficulte !== null && !submitting;

  return (
    <article className={cardStyles} aria-live="polite" data-testid="rating-card">
      <h2 className={titleStyles}>{item.mot}</h2>
      <p className={chipRowStyles}>
        <span className={chipStyles} data-chip="pos">{posLabel(item.pos)}</span>
        <span className={`${chipStyles} ${chipCategorieStyles}`} data-chip="categorie">
          {categorieLabel(item.categorie)}
        </span>
      </p>
      <blockquote className={definitionStyles}>« {item.definition} »</blockquote>
      <p className={metaStyles}>
        Style : {styleLabel(item.style)} · Difficulté annoncée : {item.forceClaimed}
      </p>

      <Likert
        label="Cette définition est :"
        ariaLabel="Qualité"
        value={qualite}
        onChange={setQualite}
        leftHint="Mauvaise"
        rightHint="Excellente"
      />

      <Likert
        label="Difficulté réelle :"
        ariaLabel="Difficulté"
        value={difficulte}
        onChange={setDifficulte}
        leftHint="Très facile"
        rightHint="Expert"
      />

      <FlagPicker value={flag} onChange={setFlag} />

      {isAuthenticated ? (
        <CorrectifField key={item.itemId} value={correctif} onChange={setCorrectif} />
      ) : null}

      <div className={submitRowStyles}>
        <Button
          variant="primary"
          disabled={!canSubmit}
          onClick={() => { void submit(); }}
        >
          {submitting ? 'Envoi…' : 'Suivant'}
        </Button>
      </div>
    </article>
  );
}
