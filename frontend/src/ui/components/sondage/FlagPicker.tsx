// Optional 4-option flag picker for /sondage. Native <select> so the
// keyboard / a11y surface comes from the platform (the four reasons are
// short and the visual chrome can be themed via Panda without writing a
// custom listbox).

import { useId } from 'react';
import { css } from 'styled-system/css';
import type { SurveyFlagReason } from '@/application/survey';

const wrapperStyles = css({
  display: 'flex',
  flexDirection: 'column',
  gap: 'xs',
});

const labelStyles = css({
  fontSize: 'body',
  fontWeight: 'semibold',
  color: 'fg',
});

const selectStyles = css({
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

const NONE_VALUE = '';

const OPTIONS: ReadonlyArray<{ value: SurveyFlagReason; label: string }> = [
  { value: 'hors_sujet', label: 'Hors-sujet' },
  { value: 'auto_reference', label: 'Auto-référence' },
  { value: 'erreur_sens', label: 'Erreur de sens' },
  { value: 'autre', label: 'Autre' },
];

export interface FlagPickerProps {
  readonly value: SurveyFlagReason | undefined;
  readonly onChange: (value: SurveyFlagReason | undefined) => void;
}

export function FlagPicker({ value, onChange }: FlagPickerProps) {
  const id = useId();
  return (
    <div className={wrapperStyles}>
      <label htmlFor={id} className={labelStyles}>Signaler un problème (optionnel)</label>
      <select
        id={id}
        className={selectStyles}
        value={value ?? NONE_VALUE}
        onChange={(event) => {
          const next = event.target.value;
          onChange(next === NONE_VALUE ? undefined : (next as SurveyFlagReason));
        }}
      >
        <option value={NONE_VALUE}>— Aucun —</option>
        {OPTIONS.map((opt) => (
          <option key={opt.value} value={opt.value}>{opt.label}</option>
        ))}
      </select>
    </div>
  );
}
