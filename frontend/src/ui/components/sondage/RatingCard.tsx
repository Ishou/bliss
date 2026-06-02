// ADR-0050 §6 a11y: 56 px touch-target minimum.

import { useEffect, useRef, useState } from 'react';
import { css, cx } from 'styled-system/css';
import type { SurveyCategorie, SurveyClient, SurveyItem, SurveyPos } from '@/application/survey';
import { Select } from '@/ui/components/primitives';
import { CategorieMultiSelect } from './CategorieMultiSelect';
import { GlossChipInput } from './GlossChipInput';
import { POS_OPTIONS, posLabel, styleLabel } from './labels';
import { SenseInput } from './SenseInput';
import { useLemmaMeta } from './useLemmaMeta';

const EMPTY_LIST: ReadonlyArray<string> = Object.freeze([]);

export type Verdict = 'GOOD' | 'BAD' | 'SKIP';

export interface RatingMeta {
  readonly targetCategories: ReadonlyArray<SurveyCategorie>;
  readonly targetSense: string;
  readonly isMultisense: boolean;
  readonly subTags: ReadonlyArray<string>;
}

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
  gridTemplateColumns: 'repeat(4, minmax(0, 1fr))',
  gap: 'sm',
});

const correctifBoxStyles = css({
  display: 'flex',
  flexDirection: 'column',
  gap: 'sm',
  border: '1px solid token(colors.accent)',
  borderRadius: 'md',
  padding: 'md',
  bg: 'surface',
});

const correctifTextareaStyles = css({
  fontSize: 'body',
  fontFamily: 'body',
  color: 'fg',
  bg: 'transparent',
  border: '1px solid token(colors.border)',
  borderRadius: 'sm',
  padding: 'sm',
  minHeight: '64px',
  resize: 'vertical',
});

const correctifActionsStyles = css({
  display: 'flex',
  gap: 'sm',
  justifyContent: 'flex-end',
});

const correctifButtonStyles = css({
  paddingInline: 'md',
  paddingBlock: 'sm',
  borderRadius: 'sm',
  fontSize: 'sm',
  fontWeight: 'semibold',
  cursor: 'pointer',
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

const verdictCorrigerStyles = css({
  bg: 'surface',
  color: 'accent',
  border: '1px solid token(colors.accent)',
  _hover: { bg: 'surfaceMuted' },
});

const shortcutStyles = css({
  fontSize: 'xs',
  fontWeight: 'normal',
  opacity: 0.75,
});

const metaInputsStyles = css({
  display: 'flex',
  flexDirection: 'column',
  gap: 'sm',
});

const multisenseRowStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  gap: '8px',
  fontSize: 'body',
  color: 'fg',
  cursor: 'pointer',
});

const multisenseCheckboxStyles = css({
  width: '18px',
  height: '18px',
  margin: 0,
  accentColor: 'token(colors.accent)',
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
});

export interface RatingCardProps {
  readonly item: SurveyItem;
  readonly onVerdict: (
    verdict: Verdict,
    latencyMs: number,
    meta: RatingMeta,
  ) => Promise<void> | void;
  readonly onCorriger: (
    correctedText: string,
    pos: SurveyPos,
    latencyMs: number,
    meta: RatingMeta,
  ) => Promise<void> | void;
  readonly disabled?: boolean;
  readonly surveyClient?: SurveyClient | null;
}

export function RatingCard({ item, onVerdict, onCorriger, disabled = false, surveyClient }: RatingCardProps) {
  const startedAtRef = useRef<number>(0);
  const [correctifText, setCorrectifText] = useState<string | null>(null);
  const [correctifPos, setCorrectifPos] = useState<SurveyPos>(item.pos);
  const [targetCategories, setTargetCategories] = useState<ReadonlyArray<SurveyCategorie>>([item.categorie]);
  const [targetSense, setTargetSense] = useState('');
  const [isMultisense, setIsMultisense] = useState(false);
  const [subTags, setSubTags] = useState<ReadonlyArray<string>>([]);

  const lemmaMeta = useLemmaMeta(surveyClient ?? null, item.mot);
  const priorSenses = lemmaMeta.data?.priorSenses ?? EMPTY_LIST;
  const priorSubTags = lemmaMeta.data?.priorSubTags ?? EMPTY_LIST;

  const meta: RatingMeta = { targetCategories, targetSense, isMultisense, subTags };

  useEffect(() => {
    setCorrectifText(null);
    setCorrectifPos(item.pos);
    setTargetCategories([item.categorie]);
    setTargetSense('');
    setIsMultisense(false);
    setSubTags([]);
  }, [item.itemId, item.pos, item.categorie]);

  // Lock arriving mid-correction collapses any open panel so the disabled state stays internally consistent.
  useEffect(() => {
    if (disabled) setCorrectifText(null);
  }, [disabled]);

  useEffect(() => {
    startedAtRef.current = performance.now();
    function handler(event: KeyboardEvent): void {
      if (disabled) return;
      if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.altKey) return;
      const target = event.target as HTMLElement | null;
      if (target && /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName)) return;
      // Ark Select renders a `<button role="combobox">`; focus stays on it while the listbox is open.
      if (target instanceof Element && target.closest('[role="combobox"], [role="listbox"]')) return;
      if (target?.isContentEditable) return;
      const key = event.key.toLowerCase();
      if (key === 'c') {
        event.preventDefault();
        setCorrectifText(item.definition);
        return;
      }
      const verdict: Verdict | null = key === 'j' ? 'BAD' : key === 'k' ? 'SKIP' : key === 'l' ? 'GOOD' : null;
      if (verdict === null) return;
      event.preventDefault();
      const latencyMs = Math.max(0, Math.round(performance.now() - startedAtRef.current));
      void onVerdict(verdict, latencyMs, { targetCategories, targetSense, isMultisense, subTags });
    }
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [item.itemId, item.definition, onVerdict, disabled, targetCategories, targetSense, isMultisense, subTags]);

  function submit(verdict: Verdict): void {
    if (disabled) return;
    const latencyMs = Math.max(0, Math.round(performance.now() - startedAtRef.current));
    void onVerdict(verdict, latencyMs, meta);
  }

  function submitCorrectif(): void {
    if (correctifText === null) return;
    const trimmed = correctifText.trim();
    const textChanged = trimmed.length > 0 && trimmed !== item.definition.trim();
    const posChanged = correctifPos !== item.pos;
    // Nothing to correct: empty text with no POS change is a no-op. POS-only fixes keep the original text.
    if (!textChanged && !posChanged) {
      setCorrectifText(null);
      return;
    }
    const latencyMs = Math.max(0, Math.round(performance.now() - startedAtRef.current));
    const text = textChanged ? trimmed : item.definition;
    setCorrectifText(null);
    void onCorriger(text, correctifPos, latencyMs, meta);
  }

  return (
    <article className={cardStyles} aria-live="polite" data-testid="rating-card">
      <h2 className={titleStyles}>{item.mot}</h2>
      <p className={chipRowStyles}>
        <span className={chipStyles} data-chip="pos">{posLabel(item.pos)}</span>
      </p>
      <blockquote className={definitionStyles}>« {item.definition} »</blockquote>
      <p className={metaStyles}>
        Style : {styleLabel(item.style)} · Difficulté annoncée : {item.forceClaimed}
      </p>

      <div
        className={verdictRowStyles}
        role="group"
        aria-label="Verdict"
        aria-keyshortcuts="j k l c"
      >
        <button
          type="button"
          className={cx(verdictButtonBase, verdictBadStyles)}
          aria-label={`Mauvaise définition pour l'indice « ${item.definition} »`}
          aria-disabled={disabled || undefined}
          data-verdict="BAD"
          onClick={() => submit('BAD')}
        >
          <span>Mauvaise</span>
          <span className={shortcutStyles}>J</span>
        </button>
        <button
          type="button"
          className={cx(verdictButtonBase, verdictSkipStyles)}
          aria-label={`Passer l'indice « ${item.definition} »`}
          aria-disabled={disabled || undefined}
          data-verdict="SKIP"
          onClick={() => submit('SKIP')}
        >
          <span>Passer</span>
          <span className={shortcutStyles}>K</span>
        </button>
        <button
          type="button"
          className={cx(verdictButtonBase, verdictGoodStyles)}
          aria-label={`Bonne définition pour l'indice « ${item.definition} »`}
          aria-disabled={disabled || undefined}
          data-verdict="GOOD"
          onClick={() => submit('GOOD')}
        >
          <span>Bonne</span>
          <span className={shortcutStyles}>L</span>
        </button>
        {disabled ? null : (
          <button
            type="button"
            className={cx(verdictButtonBase, verdictCorrigerStyles)}
            aria-label={`Corriger l'indice « ${item.definition} »`}
            data-verdict="CORRIGER"
            onClick={() => setCorrectifText(item.definition)}
          >
            <span>Corriger</span>
            <span className={shortcutStyles}>C</span>
          </button>
        )}
      </div>

      {disabled ? null : (
        <div className={metaInputsStyles} data-testid="rating-meta-inputs">
          <CategorieMultiSelect
            value={targetCategories}
            onChange={(next) => setTargetCategories(next)}
            legend="Catégories"
            minItems={1}
            maxItems={6}
            exclusiveValue="autre"
          />
          <label className={multisenseRowStyles}>
            <input
              type="checkbox"
              className={multisenseCheckboxStyles}
              data-testid="multisense-checkbox"
              checked={isMultisense}
              onChange={(e) => setIsMultisense(e.target.checked)}
            />
            <span>Plusieurs sens</span>
          </label>
          <SenseInput
            value={targetSense}
            onChange={(next) => setTargetSense(next)}
            suggestions={priorSenses}
            label="Sens cible"
            placeholder="ex. animal félin, conversation digitale…"
            disabled={isMultisense}
            bannedTerm={item.mot}
          />
          <GlossChipInput
            value={[...subTags]}
            onChange={(next) => setSubTags(next)}
            suggestions={priorSubTags}
            label="Mots-clés"
            ariaLabel="Mots-clés"
            placeholder="ex. félin, mammifère, domestique…"
            maxItems={12}
            maxLength={40}
          />
        </div>
      )}

      {correctifText !== null ? (
        <div className={correctifBoxStyles} data-testid="correctif-box">
          <Select<SurveyPos>
            label="Nature grammaticale"
            value={correctifPos}
            onValueChange={(pos) => pos && setCorrectifPos(pos)}
            options={POS_OPTIONS.map((pos) => ({ value: pos as SurveyPos, label: posLabel(pos) }))}
          />
          <label htmlFor="correctif-text" className={metaStyles}>
            Proposez une définition corrigée. Soumise comme nouvelle entrée notée « bonne » automatiquement.
          </label>
          <textarea
            id="correctif-text"
            className={correctifTextareaStyles}
            value={correctifText}
            onChange={(e) => setCorrectifText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                submitCorrectif();
              } else if (e.key === 'Escape') {
                e.preventDefault();
                setCorrectifText(null);
              }
            }}
            autoFocus
          />
          <div className={correctifActionsStyles}>
            <button
              type="button"
              className={cx(correctifButtonStyles, verdictSkipStyles)}
              onClick={() => setCorrectifText(null)}
            >
              Annuler
            </button>
            <button
              type="button"
              className={cx(correctifButtonStyles, verdictGoodStyles)}
              data-testid="correctif-submit"
              onClick={submitCorrectif}
            >
              Soumettre la correction
            </button>
          </div>
        </div>
      ) : null}
    </article>
  );
}
