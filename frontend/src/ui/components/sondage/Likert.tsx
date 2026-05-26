// Likert 1–5 input with the ARIA radiogroup pattern (spec §8). Tab
// focuses the group, ←/→ (and ↑/↓) move the active radio, Space/Enter
// select it. AZERTY-safe — number-row digits are deliberately not
// shortcuts because they require Shift on French layouts.

import { useId, useRef } from 'react';
import { css, cx } from 'styled-system/css';
import type { LikertScore } from '@/application/survey';

const scaleStyles = css({
  display: 'flex',
  alignItems: 'center',
  gap: 'sm',
  flexWrap: 'wrap',
});

const radiosStyles = css({
  display: 'flex',
  gap: 'xs',
  alignItems: 'center',
});

const radioStyles = css({
  width: '40px',
  height: '40px',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  borderRadius: '6px',
  border: '1px solid token(colors.border)',
  bg: 'surface',
  color: 'fg',
  fontFamily: 'body',
  fontSize: 'body',
  fontWeight: 'semibold',
  cursor: 'pointer',
  transition: 'background-color 120ms ease-out, color 120ms ease-out, border-color 120ms ease-out',
  _hover: { borderColor: 'accent' },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
});

const radioSelectedStyles = css({
  bg: 'accent',
  color: 'onAccent',
  borderColor: 'accent',
});

const hintStyles = css({
  fontSize: 'sm',
  color: 'fgMuted',
});

const fieldsetStyles = css({
  border: 'none',
  padding: 0,
  margin: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 'xs',
});

const legendStyles = css({
  fontSize: 'body',
  fontWeight: 'semibold',
  color: 'fg',
  padding: 0,
});

export interface LikertProps {
  readonly label: string;
  readonly ariaLabel: string;
  readonly value: LikertScore | null;
  readonly onChange: (value: LikertScore) => void;
  readonly leftHint?: string;
  readonly rightHint?: string;
}

const SCORES: ReadonlyArray<LikertScore> = [1, 2, 3, 4, 5];

export function Likert({ label, ariaLabel, value, onChange, leftHint, rightHint }: LikertProps) {
  const groupId = useId();
  const buttonsRef = useRef<Array<HTMLButtonElement | null>>([]);

  // When no value is selected yet, the first radio is tabbable (the
  // radiogroup roving-tabindex pattern). After a selection, only the
  // selected radio is tabbable so Shift+Tab leaves the group cleanly.
  const activeIndex = value === null ? 0 : SCORES.indexOf(value);

  function focusIndex(next: number): void {
    const clamped = Math.max(0, Math.min(SCORES.length - 1, next));
    const target = buttonsRef.current[clamped];
    if (target) target.focus();
  }

  function onKeyDown(event: React.KeyboardEvent<HTMLButtonElement>, index: number): void {
    if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
      event.preventDefault();
      const nextIndex = index === 0 ? SCORES.length - 1 : index - 1;
      onChange(SCORES[nextIndex]);
      focusIndex(nextIndex);
      return;
    }
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
      event.preventDefault();
      const nextIndex = index === SCORES.length - 1 ? 0 : index + 1;
      onChange(SCORES[nextIndex]);
      focusIndex(nextIndex);
      return;
    }
    if (event.key === 'Home') {
      event.preventDefault();
      onChange(SCORES[0]);
      focusIndex(0);
      return;
    }
    if (event.key === 'End') {
      event.preventDefault();
      onChange(SCORES[SCORES.length - 1]);
      focusIndex(SCORES.length - 1);
      return;
    }
    if (event.key === ' ' || event.key === 'Enter') {
      event.preventDefault();
      onChange(SCORES[index]);
    }
  }

  return (
    <fieldset className={fieldsetStyles} aria-labelledby={`${groupId}-label`}>
      <legend id={`${groupId}-label`} className={legendStyles}>{label}</legend>
      <div className={scaleStyles}>
        {leftHint ? <span className={hintStyles}>{leftHint}</span> : null}
        <div className={radiosStyles} role="radiogroup" aria-label={ariaLabel}>
          {SCORES.map((score, index) => {
            const isSelected = value === score;
            return (
              <button
                key={score}
                ref={(el) => { buttonsRef.current[index] = el; }}
                type="button"
                role="radio"
                aria-checked={isSelected}
                aria-label={String(score)}
                tabIndex={index === activeIndex ? 0 : -1}
                className={isSelected ? cx(radioStyles, radioSelectedStyles) : radioStyles}
                onClick={() => onChange(score)}
                onKeyDown={(event) => onKeyDown(event, index)}
              >
                {score}
              </button>
            );
          })}
        </div>
        {rightHint ? <span className={hintStyles}>{rightHint}</span> : null}
      </div>
    </fieldset>
  );
}
