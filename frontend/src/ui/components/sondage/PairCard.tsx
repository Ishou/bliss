// ADR-0050 §6 a11y: 56 px touch-target minimum.
// ADR-0056 pairwise verdict: LEFT_WINS / RIGHT_WINS → pair_ratings; BOTH_GOOD / BOTH_BAD → ratings; SKIP → nothing.

import { useEffect, useRef } from 'react';
import { css, cx } from 'styled-system/css';
import type { ItemPair, PairVerdict } from '@/application/survey';
import { categorieLabel, posLabel, styleLabel } from './labels';

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

const pairGridStyles = css({
  display: 'grid',
  gridTemplateColumns: { base: '1fr', md: 'repeat(2, minmax(0, 1fr))' },
  gap: 'md',
});

const sideStyles = css({
  display: 'flex',
  flexDirection: 'column',
  gap: 'sm',
  border: '1px solid token(colors.border)',
  borderRadius: 'sm',
  padding: 'md',
  minWidth: 0,
});

const sideHeadingStyles = css({
  fontSize: 'sm',
  fontWeight: 'semibold',
  color: 'fgMuted',
  margin: 0,
  textTransform: 'uppercase',
  letterSpacing: '0.05em',
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
  gridTemplateColumns: { base: 'repeat(2, minmax(0, 1fr))', md: 'repeat(5, minmax(0, 1fr))' },
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

const verdictLeftStyles = css({
  bg: 'accent',
  color: 'onAccent',
  border: '1px solid token(colors.accent)',
  _hover: { bg: 'primary.400' },
});

const verdictRightStyles = css({
  bg: 'accent',
  color: 'onAccent',
  border: '1px solid token(colors.accent)',
  _hover: { bg: 'primary.400' },
});

const verdictBothGoodStyles = css({
  bg: 'surface',
  color: 'accent',
  border: '1px solid token(colors.accent)',
  _hover: { bg: 'surfaceMuted' },
});

const verdictBothBadStyles = css({
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

const shortcutStyles = css({
  fontSize: 'xs',
  fontWeight: 'normal',
  opacity: 0.75,
});

const legendStyles = css({
  fontSize: 'xs',
  color: 'fgMuted',
  margin: 0,
});

export interface PairCardProps {
  readonly pair: ItemPair;
  readonly onVerdict: (verdict: PairVerdict, latencyMs: number) => Promise<void> | void;
}

export function PairCard({ pair, onVerdict }: PairCardProps) {
  const startedAtRef = useRef<number>(0);

  useEffect(() => {
    startedAtRef.current = performance.now();
    function handler(event: KeyboardEvent): void {
      if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.altKey) return;
      const target = event.target as HTMLElement | null;
      if (target && /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName)) return;
      if (target?.isContentEditable) return;
      const key = event.key === ' ' ? 'space' : event.key === 'Escape' ? 'escape' : event.key.toLowerCase();
      const verdict: PairVerdict | null =
        key === 'a' ? 'LEFT_WINS'
        : key === 'd' ? 'RIGHT_WINS'
        : key === 's' ? 'BOTH_GOOD'
        : key === 'x' ? 'BOTH_BAD'
        : key === 'space' || key === 'escape' ? 'SKIP'
        : null;
      if (verdict === null) return;
      event.preventDefault();
      const latencyMs = Math.max(0, Math.round(performance.now() - startedAtRef.current));
      void onVerdict(verdict, latencyMs);
    }
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [pair.left.itemId, pair.right.itemId, onVerdict]);

  function submit(verdict: PairVerdict): void {
    const latencyMs = Math.max(0, Math.round(performance.now() - startedAtRef.current));
    void onVerdict(verdict, latencyMs);
  }

  return (
    <article className={cardStyles} aria-live="polite" data-testid="pair-card">
      <h2 className={titleStyles}>{pair.mot}</h2>
      <div className={pairGridStyles}>
        <section className={sideStyles} aria-label="Définition de gauche" data-side="left">
          <h3 className={sideHeadingStyles}>Gauche</h3>
          <p className={chipRowStyles}>
            <span className={chipStyles} data-chip="pos">{posLabel(pair.left.pos)}</span>
            <span className={`${chipStyles} ${chipCategorieStyles}`} data-chip="categorie">
              {categorieLabel(pair.left.categorie)}
            </span>
          </p>
          <blockquote className={definitionStyles}>« {pair.left.definition} »</blockquote>
          <p className={metaStyles}>
            Style : {styleLabel(pair.left.style)} · Difficulté annoncée : {pair.left.forceClaimed}
          </p>
        </section>
        <section className={sideStyles} aria-label="Définition de droite" data-side="right">
          <h3 className={sideHeadingStyles}>Droite</h3>
          <p className={chipRowStyles}>
            <span className={chipStyles} data-chip="pos">{posLabel(pair.right.pos)}</span>
            <span className={`${chipStyles} ${chipCategorieStyles}`} data-chip="categorie">
              {categorieLabel(pair.right.categorie)}
            </span>
          </p>
          <blockquote className={definitionStyles}>« {pair.right.definition} »</blockquote>
          <p className={metaStyles}>
            Style : {styleLabel(pair.right.style)} · Difficulté annoncée : {pair.right.forceClaimed}
          </p>
        </section>
      </div>

      <div
        className={verdictRowStyles}
        role="group"
        aria-label="Verdict pairwise"
        aria-keyshortcuts="a d s x space escape"
      >
        <button
          type="button"
          className={cx(verdictButtonBase, verdictLeftStyles)}
          aria-label="Préférer la définition de gauche"
          data-verdict="LEFT_WINS"
          onClick={() => submit('LEFT_WINS')}
        >
          <span>← Préférer celle-ci</span>
          <span className={shortcutStyles}>A</span>
        </button>
        <button
          type="button"
          className={cx(verdictButtonBase, verdictRightStyles)}
          aria-label="Préférer la définition de droite"
          data-verdict="RIGHT_WINS"
          onClick={() => submit('RIGHT_WINS')}
        >
          <span>Préférer celle-ci →</span>
          <span className={shortcutStyles}>D</span>
        </button>
        <button
          type="button"
          className={cx(verdictButtonBase, verdictBothGoodStyles)}
          aria-label="Les deux définitions sont bonnes"
          data-verdict="BOTH_GOOD"
          onClick={() => submit('BOTH_GOOD')}
        >
          <span>Les deux bonnes</span>
          <span className={shortcutStyles}>S</span>
        </button>
        <button
          type="button"
          className={cx(verdictButtonBase, verdictBothBadStyles)}
          aria-label="Les deux définitions sont mauvaises"
          data-verdict="BOTH_BAD"
          onClick={() => submit('BOTH_BAD')}
        >
          <span>Les deux mauvaises</span>
          <span className={shortcutStyles}>X</span>
        </button>
        <button
          type="button"
          className={cx(verdictButtonBase, verdictSkipStyles)}
          aria-label="Passer cette paire"
          data-verdict="SKIP"
          onClick={() => submit('SKIP')}
        >
          <span>Passer</span>
          <span className={shortcutStyles}>Espace</span>
        </button>
      </div>
      <p className={legendStyles}>
        Raccourcis : A préférer gauche · D préférer droite · S les deux bonnes · X les deux mauvaises · Espace passer.
      </p>
    </article>
  );
}
