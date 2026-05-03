import {
  RadioGroup as ArkRadioGroup,
  type RadioGroupValueChangeDetails,
} from '@ark-ui/react/radio-group';
import type { ReactNode } from 'react';
import { css, cx } from 'styled-system/css';

// Project-local RadioGroup primitive — wraps Ark UI's `RadioGroup` so
// the WaitingRoom grid-size picker stops rendering 4 separate
// `<input type="radio">` elements with hand-rolled label / fieldset
// scaffolding.
//
// The semantic shape changes from "fieldset > inputs" to Ark's
// "Root > Label + Item(Hidden+Control+Text)" — which still produces a
// `radio` role per item plus a `radiogroup` role at the root, so
// existing test assertions (`getByRole('radio', { name: '7×7' })`) keep
// matching. Ark also handles arrow-key navigation between options out of
// the box, which the legacy hand-rolled inputs got "for free" from the
// browser only because they shared a `name`.
//
// Visual layer is owned by Bliss (ADR-0002 §2): the styles below come
// from the legacy `radioGroup` / `radioLabel` declarations so the
// rendered chip layout is unchanged.

const labelStyles = css({
  fontSize: 'md',
  fontWeight: 'bold',
  color: 'leaf.700',
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
  gap: 'xs',
  paddingBlock: 'xs',
  paddingInline: 'sm',
  borderRadius: 'sm',
  border: '1px solid token(colors.border)',
  bg: 'surface',
  cursor: 'pointer',
  fontSize: 'sm',
  // Highlight the selected option so the picker reads at a glance.
  // Ark sets `data-state="checked"` on the matching item.
  '&[data-state="checked"]': {
    borderColor: 'leaf.700',
    bg: 'leaf.50',
    color: 'leaf.700',
    fontWeight: 'bold',
  },
  _disabled: { opacity: 0.5, cursor: 'not-allowed' },
});

export interface RadioOption<TValue extends string> {
  readonly value: TValue;
  readonly label: ReactNode;
}

export interface RadioGroupProps<TValue extends string> {
  readonly label: ReactNode;
  readonly value: TValue;
  readonly onValueChange: (value: TValue) => void;
  readonly options: readonly RadioOption<TValue>[];
  readonly name?: string;
  readonly groupClassName?: string;
}

// Generic over the option type so callers don't lose the discriminator
// when they read back the chosen value (e.g. WaitingRoom's `5 | 7 | 9 | 11`
// stringified to '5' / '7' / '9' / '11').
export function RadioGroup<TValue extends string>({
  label,
  value,
  onValueChange,
  options,
  name,
  groupClassName,
}: RadioGroupProps<TValue>) {
  const handleChange = (details: RadioGroupValueChangeDetails) => {
    onValueChange(details.value as TValue);
  };

  return (
    <ArkRadioGroup.Root
      value={value}
      onValueChange={handleChange}
      name={name}
    >
      <ArkRadioGroup.Label className={labelStyles}>{label}</ArkRadioGroup.Label>
      <div className={cx(groupStyles, groupClassName)}>
        {options.map((option) => (
          <ArkRadioGroup.Item
            key={option.value}
            value={option.value}
            className={itemStyles}
          >
            <ArkRadioGroup.ItemHiddenInput />
            <ArkRadioGroup.ItemText>{option.label}</ArkRadioGroup.ItemText>
          </ArkRadioGroup.Item>
        ))}
      </div>
    </ArkRadioGroup.Root>
  );
}
