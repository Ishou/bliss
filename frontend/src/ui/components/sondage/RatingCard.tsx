// ADR-0050 §6 a11y: 56 px touch-target minimum.

import { useEffect, useRef } from 'react';
import { css, cx } from 'styled-system/css';
import type { SurveyItem } from '@/application/survey';
import { categorieLabel, posLabel, styleLabel } from './labels';

export type Verdict = 'GOOD' | 'BAD' | 'SKIP';

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

const verdictRowStyles = css({
  display: 'grid',
  gridTemplateColumns: 'repeat(3, minmax(0, 1fr))',
  gap: 'sm',
});

const verdictButtonBase = css({
  minHeight: '56px',
  minWidth: '56px',
  display: 'inline-flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'center',
  paddingInline: 'md',
  paddingBlock: 'sm',
  fontFamily: 'body',
  fontSize: 'body',
  fontWeight: 'bold',
  borderRadius: '6px',
  cursor: 'pointer',
  transition: 'background-color 120ms ease-out, border-color 120ms ease-out, opacity 120ms ease-out',
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
  _disabled: { opacity: 0.5, cursor: 'not-allowed' },
});

const verdictBadStyles = css({
  bg: 'errorBg',
  color: 'errorText',
  border: '1px solid token(colors.error)',
  _hover: { bg: 'terra.200' },
});

const verdictSkipStyles = css({
  bg: 'surface',
  color: 'fgMuted',
  border: '1px solid token(colors.border)',
  _hover: { bg: 'surfaceMuted' },
});

const verdictGoodStyles = css({
  bg: 'accent',
  color: 'onAccent',
  border: '1px solid token(colors.accent)',
  _hover: { bg: 'primary.400' },
});

const shortcutStyles = css({
  fontSize: 'xs',
  fontWeight: 'normal',
  opacity: 0.75,
});

export interface RatingCardProps {
  readonly item: SurveyItem;
  readonly onVerdict: (verdict: Verdict, latencyMs: number) => Promise<void> | void;
}

export function RatingCard({ item, onVerdict }: RatingCardProps) {
  const startedAtRef = useRef<number>(0);

  useEffect(() => {
    startedAtRef.current = performance.now();
    function handler(event: KeyboardEvent): void {
      if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.altKey) return;
      const target = event.target as HTMLElement | null;
      // Ignore shortcuts while the focus is in a form control (defensive future-proofing).
      if (target && /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName)) return;
      if (target?.isContentEditable) return;
      const key = event.key.toLowerCase();
      const verdict: Verdict | null = key === 'j' ? 'BAD' : key === 'k' ? 'SKIP' : key === 'l' ? 'GOOD' : null;
      if (verdict === null) return;
      event.preventDefault();
      const latencyMs = Math.max(0, Math.round(performance.now() - startedAtRef.current));
      void onVerdict(verdict, latencyMs);
    }
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [item.itemId, onVerdict]);

  function submit(verdict: Verdict): void {
    const latencyMs = Math.max(0, Math.round(performance.now() - startedAtRef.current));
    void onVerdict(verdict, latencyMs);
  }

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

      <div
        className={verdictRowStyles}
        role="group"
        aria-label="Verdict"
        aria-keyshortcuts="j k l"
      >
        <button
          type="button"
          className={cx(verdictButtonBase, verdictBadStyles)}
          aria-label={`BAD pour l'indice « ${item.definition} »`}
          data-verdict="BAD"
          onClick={() => submit('BAD')}
        >
          <span>Mauvaise</span>
          <span className={shortcutStyles}>J</span>
        </button>
        <button
          type="button"
          className={cx(verdictButtonBase, verdictSkipStyles)}
          aria-label={`SKIP pour l'indice « ${item.definition} »`}
          data-verdict="SKIP"
          onClick={() => submit('SKIP')}
        >
          <span>Passer</span>
          <span className={shortcutStyles}>K</span>
        </button>
        <button
          type="button"
          className={cx(verdictButtonBase, verdictGoodStyles)}
          aria-label={`GOOD pour l'indice « ${item.definition} »`}
          data-verdict="GOOD"
          onClick={() => submit('GOOD')}
        >
          <span>Bonne</span>
          <span className={shortcutStyles}>L</span>
        </button>
      </div>
    </article>
  );
}
