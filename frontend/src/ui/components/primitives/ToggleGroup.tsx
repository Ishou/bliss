import {
  ToggleGroup as ArkToggleGroup,
  type ToggleGroupValueChangeDetails,
} from '@ark-ui/react/toggle-group';
import { useId, type ReactNode } from 'react';
import { css, cx } from 'styled-system/css';

// Project-local ToggleGroup primitive — wraps Ark UI's `ToggleGroup` so the
// WaitingRoom grid-size picker reads as a row of toggle buttons rather than
// a list of radio inputs (more idiomatic mots-fléchés UX). API mirrors the
// sibling `RadioGroup` so call sites can swap `RadioGroup<T>` →
// `ToggleGroup<T>` 1:1; the primitive hard-codes `multiple={false}`.
//
// Under the hood zag still emits `role="radiogroup"` on the root and
// `role="radio"` (with `aria-checked`) on each item in single-select mode,
// so the a11y tree is unchanged from `RadioGroup`. The visual difference is
// that items render as real `<button>` chips with a pressed/unpressed
// state rather than hidden `<input type="radio">` + label scaffolding.
// Arrow-key navigation between options is handled by Ark.
//
// Visual layer is owned by Bliss (ADR-0002 §2): active item uses the
// `accentHover` fill that matches the primary `Button`; inactive items
// follow the `ghost` Button rhythm. ToggleGroup has no form-name
// semantics, so the `name` prop is purely an id prefix for
// `aria-labelledby` wiring.

const labelStyles = css({
  fontSize: 'md',
  fontWeight: 'bold',
  color: 'accent',
  margin: 0,
  display: 'block',
  marginBottom: 'xs',
});

const groupStyles = css({
  display: 'flex',
  flexWrap: 'wrap',
  gap: 'sm',
});

const itemStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  gap: 'xs',
  paddingBlock: 'xs',
  paddingInline: 'sm',
  borderRadius: 'sm',
  border: '1px solid token(colors.border)',
  bg: 'surface',
  color: 'fg',
  fontFamily: 'body',
  fontSize: 'sm',
  fontWeight: 'semibold',
  cursor: 'pointer',
  // Highlight the pressed option so the picker reads at a glance.
  // Ark sets `data-state="on"` on the selected (pressed) item.
  // Pressed-state text uses `onAccent` (white) so it stays readable
  // on the dark-moss fill. Was `fg` under the charbon palette
  // (where `fg` was light text on dark surfaces); under ADR-0043's
  // papier palette `fg` is forêt profonde and would be invisible on
  // deep moss. `onAccent` is the role this string always meant.
  '&[data-state="on"]': {
    bg: 'accentHover',
    borderColor: 'accentHover',
    color: 'onAccent',
  },
  _focusVisible: {
    outline: '3px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
  _hover: { bg: 'accentBg' },
  // The hover token must not erase the selected fill.
  '&[data-state="on"]:hover': { bg: 'primary.900' },
  _disabled: { opacity: 0.5, cursor: 'not-allowed' },
});

export interface ToggleGroupOption<TValue extends string> {
  readonly value: TValue;
  readonly label: ReactNode;
}

export interface ToggleGroupProps<TValue extends string> {
  readonly label: ReactNode;
  readonly value: TValue;
  readonly onValueChange: (value: TValue) => void;
  readonly options: readonly ToggleGroupOption<TValue>[];
  readonly name?: string;
  readonly groupClassName?: string;
}

// Generic over the option type so callers don't lose the discriminator
// when they read back the chosen value (e.g. WaitingRoom's `'5' | '7' | …`).
export function ToggleGroup<TValue extends string>({
  label, value, onValueChange, options, name, groupClassName,
}: ToggleGroupProps<TValue>) {
  // ToggleGroup has no `Label` part (unlike RadioGroup) — link the
  // external label via `aria-labelledby` so screen readers announce it.
  const generatedId = useId();
  const labelId = `${name ?? generatedId}-toggle-group-label`;

  const handleChange = (details: ToggleGroupValueChangeDetails) => {
    // zag emits `value` as `string[]` even in single-select mode. Empty
    // array means the user toggled the pressed item off; we swallow that
    // so the picker behaves like a radio group (one option always
    // selected). Multi-select call sites should use `@ark-ui/react` direct.
    const next = details.value[0];
    if (next != null) onValueChange(next as TValue);
  };

  return (
    <div>
      <span id={labelId} className={labelStyles}>{label}</span>
      <ArkToggleGroup.Root
        value={[value]}
        onValueChange={handleChange}
        multiple={false}
        aria-labelledby={labelId}
        className={cx(groupStyles, groupClassName)}
      >
        {options.map((option) => (
          <ArkToggleGroup.Item
            key={option.value}
            value={option.value}
            className={itemStyles}
          >
            {option.label}
          </ArkToggleGroup.Item>
        ))}
      </ArkToggleGroup.Root>
    </div>
  );
}
