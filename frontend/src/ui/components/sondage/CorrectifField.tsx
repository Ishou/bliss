// Optional `correctif` text + style for authenticated raters. Composes
// an uncontrolled text input (ADR-0002 §4) with a small style <select>.
// The parent reads the current values at submit time via the callback
// signature `(value | undefined) => void`, fired only on user-visible
// edits — no React state churn while the user is typing.

import { useId, useRef, useState } from 'react';
import { css } from 'styled-system/css';
import type { SurveyCorrectif, SurveyStyle } from '@/application/survey';

const wrapperStyles = css({
  display: 'flex',
  flexDirection: 'column',
  gap: 'xs',
  paddingBlock: 'sm',
  borderTop: '1px solid token(colors.border)',
  borderBottom: '1px solid token(colors.border)',
});

const fieldRowStyles = css({
  display: 'grid',
  gridTemplateColumns: { base: '1fr', md: '1fr 200px' },
  gap: 'sm',
  alignItems: 'end',
});

const labelStyles = css({
  fontSize: 'body',
  fontWeight: 'semibold',
  color: 'fg',
});

const inputStyles = css({
  fontFamily: 'body',
  fontSize: 'body',
  color: 'fg',
  bg: 'surface',
  border: '1px solid token(colors.border)',
  borderRadius: '6px',
  paddingInline: 'sm',
  paddingBlock: 'xs',
  minHeight: '40px',
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
});

const noticeStyles = css({
  fontSize: 'sm',
  color: 'fgMuted',
});

const STYLE_OPTIONS: ReadonlyArray<{ value: SurveyStyle; label: string }> = [
  { value: 'definition_directe', label: 'Définition directe' },
  { value: 'periphrase', label: 'Périphrase' },
  { value: 'metonymie', label: 'Métonymie' },
  { value: 'fonction_role', label: 'Fonction / rôle' },
  { value: 'calembour', label: 'Calembour' },
  { value: 'culturel', label: 'Culturel' },
  { value: 'cryptique', label: 'Cryptique' },
  { value: 'cryptique_morphologique', label: 'Cryptique morphologique' },
  { value: 'technique', label: 'Technique' },
];

export interface CorrectifFieldProps {
  // The parent owns the canonical value; this component is uncontrolled
  // for the text input (ADR-0002 §4) and re-broadcasts via onChange on
  // each user-visible edit. `value` is only read on first mount to seed
  // the initial defaultValue / select state.
  readonly value: SurveyCorrectif | undefined;
  readonly onChange: (next: SurveyCorrectif | undefined) => void;
}

export function CorrectifField({ value, onChange }: CorrectifFieldProps) {
  const textId = useId();
  const styleId = useId();
  const textRef = useRef<HTMLInputElement>(null);
  // Style is a small enum so a controlled <select> is fine — no per-keystroke churn.
  const [style, setStyle] = useState<SurveyStyle>(value?.style ?? 'definition_directe');

  function broadcast(text: string, nextStyle: SurveyStyle): void {
    const trimmed = text.trim();
    if (trimmed.length === 0) {
      onChange(undefined);
      return;
    }
    onChange({ text: trimmed, style: nextStyle });
  }

  return (
    <div className={wrapperStyles}>
      <span className={labelStyles}>Proposer une meilleure définition (optionnel)</span>
      <div className={fieldRowStyles}>
        <div className={css({ display: 'flex', flexDirection: 'column', gap: 'xs' })}>
          <label htmlFor={textId} className={css({ fontSize: 'sm', color: 'fgMuted' })}>
            Définition alternative
          </label>
          <input
            id={textId}
            ref={textRef}
            type="text"
            className={inputStyles}
            defaultValue={value?.text ?? ''}
            maxLength={60}
            minLength={2}
            placeholder="ex. animal domestique à moustaches"
            onInput={(event) => {
              broadcast(event.currentTarget.value, style);
            }}
          />
        </div>
        <div className={css({ display: 'flex', flexDirection: 'column', gap: 'xs' })}>
          <label htmlFor={styleId} className={css({ fontSize: 'sm', color: 'fgMuted' })}>
            Style proposé
          </label>
          <select
            id={styleId}
            className={inputStyles}
            value={style}
            onChange={(event) => {
              const next = event.target.value as SurveyStyle;
              setStyle(next);
              broadcast(textRef.current?.value ?? '', next);
            }}
          >
            {STYLE_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>{opt.label}</option>
            ))}
          </select>
        </div>
      </div>
      <p className={noticeStyles}>
        Les corrections proposées rejoignent notre corpus comme indices anonymes, sans lien avec votre compte.
      </p>
    </div>
  );
}
