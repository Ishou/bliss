// ADR-0050: checkbox group in a labelled fieldset; min/max enforced, polite live region on change.

import { useId, useState } from 'react';
import { css } from 'styled-system/css';
import type { SurveyCategorie } from '@/application/survey';
import { CATEGORIE_OPTIONS, categorieLabel } from './labels';

const fieldsetStyles = css({
  display: 'flex',
  flexDirection: 'column',
  gap: 'xs',
  border: 'none',
  margin: 0,
  padding: 0,
});

const legendStyles = css({
  fontSize: 'body',
  fontWeight: 'semibold',
  color: 'fg',
  padding: 0,
});

const optionsStyles = css({
  display: 'flex',
  flexWrap: 'wrap',
  gap: 'xs',
});

const optionStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  gap: '6px',
  paddingInline: 'sm',
  paddingBlock: '6px',
  fontSize: 'sm',
  color: 'fg',
  border: '1px solid token(colors.border)',
  borderRadius: 'sm',
  cursor: 'pointer',
  bg: 'surface',
  _focusWithin: { borderColor: 'accent' },
  '&[data-checked="true"]': { borderColor: 'accent', color: 'accent', fontWeight: 'semibold' },
});

const checkboxStyles = css({
  width: '16px',
  height: '16px',
  margin: 0,
  accentColor: 'token(colors.accent)',
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
});

const liveRegionStyles = css({
  position: 'absolute',
  width: '1px',
  height: '1px',
  margin: '-1px',
  padding: 0,
  overflow: 'hidden',
  clip: 'rect(0 0 0 0)',
  whiteSpace: 'nowrap',
  border: 0,
});

export interface CategorieMultiSelectProps {
  readonly value: ReadonlyArray<SurveyCategorie>;
  readonly onChange: (next: ReadonlyArray<SurveyCategorie>) => void;
  readonly legend: string;
  readonly minItems?: number;
  readonly maxItems?: number;
  readonly disabled?: boolean;
}

export function CategorieMultiSelect({
  value,
  onChange,
  legend,
  minItems = 1,
  maxItems = 6,
  disabled = false,
}: CategorieMultiSelectProps) {
  const groupId = useId();
  const [announce, setAnnounce] = useState('');
  const selected = new Set(value);

  function toggle(cat: SurveyCategorie): void {
    if (disabled) return;
    if (selected.has(cat)) {
      // The min-1 floor keeps the seed selection from being fully cleared.
      if (value.length <= minItems) return;
      onChange(value.filter((c) => c !== cat));
      setAnnounce(`${categorieLabel(cat)} retirée`);
      return;
    }
    if (value.length >= maxItems) return;
    onChange([...value, cat]);
    setAnnounce(`${categorieLabel(cat)} ajoutée`);
  }

  return (
    <fieldset className={fieldsetStyles} data-testid="categorie-multiselect">
      <legend className={legendStyles}>{legend}</legend>
      <div className={optionsStyles}>
        {CATEGORIE_OPTIONS.map((opt) => {
          const cat = opt as SurveyCategorie;
          const checked = selected.has(cat);
          return (
            <label key={cat} className={optionStyles} data-checked={checked} data-categorie={cat}>
              <input
                type="checkbox"
                className={checkboxStyles}
                name={`${groupId}-categorie`}
                checked={checked}
                disabled={disabled || (!checked && value.length >= maxItems)}
                onChange={() => toggle(cat)}
              />
              <span>{categorieLabel(cat)}</span>
            </label>
          );
        })}
      </div>
      <span role="status" aria-live="polite" aria-atomic="true" className={liveRegionStyles}>
        {announce}
      </span>
    </fieldset>
  );
}
