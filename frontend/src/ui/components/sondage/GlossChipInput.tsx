// ADR-0050: combobox + listbox semantics, polite live region on chip changes.

import { useId, useMemo, useRef, useState } from 'react';
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
  flexWrap: 'wrap',
  alignItems: 'center',
  gap: 'xs',
  border: '1px solid token(colors.border)',
  borderRadius: 'sm',
  padding: 'sm',
  bg: 'surface',
  _focusWithin: { borderColor: 'accent' },
});

const chipStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  gap: '4px',
  paddingInline: 'sm',
  paddingBlock: '4px',
  fontSize: 'sm',
  fontWeight: 'semibold',
  color: 'fg',
  bg: 'surfaceMuted',
  border: '1px solid token(colors.border)',
  borderRadius: 'sm',
});

const chipRemoveStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  width: '20px',
  height: '20px',
  border: 'none',
  bg: 'transparent',
  color: 'fgMuted',
  cursor: 'pointer',
  borderRadius: '999px',
  fontSize: 'sm',
  _hover: { color: 'fg', bg: 'surface' },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '1px',
  },
});

const inputStyles = css({
  flex: '1 1 auto',
  minWidth: '8ch',
  border: 'none',
  outline: 'none',
  bg: 'transparent',
  color: 'fg',
  fontFamily: 'body',
  fontSize: 'body',
  paddingBlock: '4px',
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

const optionActiveStyles = css({
  bg: 'surfaceMuted',
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

export interface GlossChipInputProps {
  readonly value: ReadonlyArray<string>;
  readonly onChange: (next: ReadonlyArray<string>) => void;
  readonly suggestions: ReadonlyArray<string>;
  readonly ariaLabel: string;
  readonly label?: string;
  readonly placeholder?: string;
  readonly maxItems?: number;
  readonly maxLength?: number;
  readonly disabled?: boolean;
  // ADR-0061 binding rule: a gloss must not repeat the lemma — the trained model already sees it in the row.
  readonly bannedTerm?: string;
}

export function GlossChipInput({
  value,
  onChange,
  suggestions,
  ariaLabel,
  label,
  placeholder,
  maxItems = 8,
  maxLength = 80,
  disabled = false,
  bannedTerm,
}: GlossChipInputProps) {
  const inputId = useId();
  const listboxId = `${inputId}-listbox`;
  const liveRegionId = `${inputId}-live`;
  const [typed, setTyped] = useState('');
  const [activeIndex, setActiveIndex] = useState(0);
  const [open, setOpen] = useState(false);
  const [announce, setAnnounce] = useState('');
  const inputRef = useRef<HTMLInputElement | null>(null);

  const normalizedExisting = useMemo(
    () => new Set(value.map(normalizeForMatch)),
    [value],
  );

  const filtered = useMemo(() => {
    const needle = normalizeForMatch(typed);
    return suggestions.filter((s) => {
      const n = normalizeForMatch(s);
      if (normalizedExisting.has(n)) return false;
      if (!needle) return true;
      return n.includes(needle);
    });
  }, [suggestions, typed, normalizedExisting]);

  function commit(raw: string): void {
    const trimmed = raw.trim();
    if (trimmed === '') return;
    if (trimmed.length > maxLength) return;
    if (value.length >= maxItems) return;
    if (bannedTerm && normalizeForMatch(trimmed).includes(normalizeForMatch(bannedTerm))) {
      setTyped('');
      return;
    }
    if (normalizedExisting.has(normalizeForMatch(trimmed))) {
      setTyped('');
      return;
    }
    const next = [...value, trimmed];
    onChange(next);
    setAnnounce(`Ajouté : ${trimmed}`);
    setTyped('');
    setActiveIndex(0);
  }

  function removeAt(index: number): void {
    const removed = value[index];
    const next = value.filter((_, i) => i !== index);
    onChange(next);
    setAnnounce(`Retiré : ${removed}`);
    inputRef.current?.focus();
  }

  function onInputKeyDown(event: React.KeyboardEvent<HTMLInputElement>): void {
    if (event.key === 'Enter' || event.key === ',') {
      event.preventDefault();
      if (open && filtered[activeIndex]) {
        commit(filtered[activeIndex]);
      } else {
        commit(typed);
      }
      return;
    }
    if (event.key === 'Backspace' && typed === '' && value.length > 0) {
      event.preventDefault();
      removeAt(value.length - 1);
      return;
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setOpen(true);
      if (filtered.length > 0) {
        setActiveIndex((i) => (i + 1) % filtered.length);
      }
      return;
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      setOpen(true);
      if (filtered.length > 0) {
        setActiveIndex((i) => (i - 1 + filtered.length) % filtered.length);
      }
      return;
    }
    if (event.key === 'Escape') {
      if (open) {
        event.preventDefault();
        setOpen(false);
      }
    }
  }

  const showList = open && filtered.length > 0 && !disabled;

  return (
    <div className={fieldStyles} data-testid={`gloss-${ariaLabel}`}>
      {label ? (
        <label htmlFor={inputId} className={labelStyles}>{label}</label>
      ) : null}
      <div className={comboStyles}>
        {value.map((chip, index) => (
          <span key={`${chip}-${index}`} className={chipStyles} data-chip-value={chip}>
            <span>{chip}</span>
            {disabled ? null : (
              <button
                type="button"
                className={chipRemoveStyles}
                aria-label={`Retirer ${chip}`}
                onClick={() => removeAt(index)}
              >
                ×
              </button>
            )}
          </span>
        ))}
        <input
          ref={inputRef}
          id={inputId}
          type="text"
          role="combobox"
          aria-label={ariaLabel}
          aria-autocomplete="list"
          aria-expanded={showList}
          aria-controls={listboxId}
          aria-activedescendant={showList ? `${listboxId}-opt-${activeIndex}` : undefined}
          className={inputStyles}
          value={typed}
          placeholder={value.length === 0 ? placeholder : undefined}
          maxLength={maxLength}
          disabled={disabled || value.length >= maxItems}
          onChange={(e) => {
            setTyped(e.target.value);
            setOpen(true);
            setActiveIndex(0);
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => {
            // Delay close so option mousedown can still register; commit-on-blur keeps content.
            setTimeout(() => setOpen(false), 120);
          }}
          onKeyDown={onInputKeyDown}
        />
        {showList ? (
          <ul
            id={listboxId}
            role="listbox"
            aria-label={ariaLabel}
            className={listboxStyles}
          >
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
                    commit(s);
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
      <span
        id={liveRegionId}
        role="status"
        aria-live="polite"
        aria-atomic="true"
        className={liveRegionStyles}
      >
        {announce}
      </span>
    </div>
  );
}
