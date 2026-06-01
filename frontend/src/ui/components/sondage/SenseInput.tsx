// ADR-0050: single-value combobox + listbox; ADR-0061: gloss must not repeat the lemma.

import { useId, useMemo, useState } from 'react';
import { css, cx } from 'styled-system/css';
import { normalizeForMatch } from '@/application/survey';

const fieldStyles = css({
  display: 'flex',
  flexDirection: 'column',
  gap: 'xs',
});

const labelStyles = css({
  fontSize: 'body',
  fontWeight: 'semibold',
  color: 'fg',
});

const comboStyles = css({
  position: 'relative',
  display: 'flex',
  border: '1px solid token(colors.border)',
  borderRadius: 'sm',
  bg: 'surface',
  _focusWithin: { borderColor: 'accent' },
});

const inputStyles = css({
  flex: '1 1 auto',
  minWidth: 0,
  border: 'none',
  outline: 'none',
  bg: 'transparent',
  color: 'fg',
  fontFamily: 'body',
  fontSize: 'body',
  padding: 'sm',
  _disabled: { opacity: 0.5, cursor: 'not-allowed' },
});

const listboxStyles = css({
  position: 'absolute',
  top: 'calc(100% + 4px)',
  left: 0,
  right: 0,
  zIndex: 10,
  maxHeight: '200px',
  overflowY: 'auto',
  margin: 0,
  padding: 0,
  listStyle: 'none',
  bg: 'surface',
  border: '1px solid token(colors.border)',
  borderRadius: 'sm',
  boxShadow: '0 4px 12px rgba(0,0,0,0.08)',
});

const optionStyles = css({
  paddingInline: 'sm',
  paddingBlock: 'xs',
  fontSize: 'body',
  color: 'fg',
  cursor: 'pointer',
  _hover: { bg: 'surfaceMuted' },
});

const optionActiveStyles = css({ bg: 'surfaceMuted' });

const hintStyles = css({
  fontSize: 'xs',
  color: 'errorText',
  margin: 0,
});

export interface SenseInputProps {
  readonly value: string;
  readonly onChange: (next: string) => void;
  readonly suggestions: ReadonlyArray<string>;
  readonly label: string;
  readonly placeholder?: string;
  readonly maxLength?: number;
  readonly disabled?: boolean;
  // ADR-0061: a sense gloss must not repeat the lemma — the row already carries it.
  readonly bannedTerm?: string;
}

export function SenseInput({
  value,
  onChange,
  suggestions,
  label,
  placeholder,
  maxLength = 80,
  disabled = false,
  bannedTerm,
}: SenseInputProps) {
  const inputId = useId();
  const listboxId = `${inputId}-listbox`;
  const [activeIndex, setActiveIndex] = useState(0);
  const [open, setOpen] = useState(false);

  const filtered = useMemo(() => {
    const needle = normalizeForMatch(value);
    return suggestions.filter((s) => {
      const n = normalizeForMatch(s);
      if (n === needle) return false;
      if (!needle) return true;
      return n.includes(needle);
    });
  }, [suggestions, value]);

  const repeatsLemma =
    !!bannedTerm &&
    value.trim() !== '' &&
    normalizeForMatch(value).includes(normalizeForMatch(bannedTerm));

  const showList = open && filtered.length > 0 && !disabled;

  function pick(s: string): void {
    onChange(s);
    setOpen(false);
  }

  function onKeyDown(event: React.KeyboardEvent<HTMLInputElement>): void {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setOpen(true);
      if (filtered.length > 0) setActiveIndex((i) => (i + 1) % filtered.length);
      return;
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      setOpen(true);
      if (filtered.length > 0) setActiveIndex((i) => (i - 1 + filtered.length) % filtered.length);
      return;
    }
    if (event.key === 'Enter' && showList && filtered[activeIndex]) {
      event.preventDefault();
      pick(filtered[activeIndex]);
      return;
    }
    if (event.key === 'Escape' && open) {
      event.preventDefault();
      setOpen(false);
    }
  }

  return (
    <div className={fieldStyles} data-testid="sense-input">
      <label htmlFor={inputId} className={labelStyles}>{label}</label>
      <div className={comboStyles}>
        <input
          id={inputId}
          type="text"
          role="combobox"
          aria-label={label}
          aria-autocomplete="list"
          aria-expanded={showList}
          aria-controls={listboxId}
          aria-invalid={repeatsLemma || undefined}
          aria-activedescendant={showList ? `${listboxId}-opt-${activeIndex}` : undefined}
          className={inputStyles}
          value={value}
          placeholder={placeholder}
          maxLength={maxLength}
          disabled={disabled}
          onChange={(e) => {
            onChange(e.target.value);
            setOpen(true);
            setActiveIndex(0);
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => setTimeout(() => setOpen(false), 120)}
          onKeyDown={onKeyDown}
        />
        {showList ? (
          <ul id={listboxId} role="listbox" aria-label={label} className={listboxStyles}>
            {filtered.map((s, idx) => {
              const isActive = idx === activeIndex;
              return (
                <li
                  key={`${s}-${idx}`}
                  id={`${listboxId}-opt-${idx}`}
                  role="option"
                  aria-selected={isActive}
                  className={isActive ? cx(optionStyles, optionActiveStyles) : optionStyles}
                  onMouseDown={(e) => {
                    e.preventDefault();
                    pick(s);
                  }}
                  onMouseEnter={() => setActiveIndex(idx)}
                >
                  {s}
                </li>
              );
            })}
          </ul>
        ) : null}
      </div>
      {repeatsLemma ? (
        <p className={hintStyles} role="alert">Le sens ne doit pas répéter le mot.</p>
      ) : null}
    </div>
  );
}
