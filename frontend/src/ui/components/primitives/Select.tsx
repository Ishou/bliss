import {
  Select as ArkSelect,
  createListCollection,
  type SelectValueChangeDetails,
} from '@ark-ui/react/select';
import { Portal } from '@ark-ui/react/portal';
import { useMemo, type ReactNode } from 'react';
import { css, cx } from 'styled-system/css';

// Project-local Select primitive — wraps Ark UI's headless `Select` so the
// visual rhythm matches the rest of the form controls (Likert, TextField,
// Button). The native `<select>` previously used in FlagPicker rendered
// the OS-native dropdown which diverged from the brand on every platform.

const rootStyles = css({
  display: 'flex',
  flexDirection: 'column',
  gap: 'xs',
});

const labelStyles = css({
  fontSize: 'md',
  fontWeight: 'bold',
  color: 'accent',
  margin: 0,
});

const triggerStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 'sm',
  minHeight: '40px',
  paddingBlock: 'sm',
  paddingInline: 'sm',
  borderRadius: 'sm',
  border: '1px solid token(colors.border)',
  bg: 'surface',
  color: 'fg',
  fontFamily: 'body',
  fontSize: 'body',
  cursor: 'pointer',
  textAlign: 'left',
  _focusVisible: {
    outline: '3px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
  _disabled: { opacity: 0.5, cursor: 'not-allowed' },
});

const indicatorStyles = css({
  color: 'fgMuted',
  fontSize: 'sm',
  // Arrow rotates when the listbox is open — `data-state="open"` is set by Ark.
  transition: 'transform 120ms ease-out',
  '&[data-state="open"]': { transform: 'rotate(180deg)' },
});

const contentStyles = css({
  display: 'flex',
  flexDirection: 'column',
  bg: 'surface',
  border: '1px solid token(colors.border)',
  borderRadius: 'sm',
  boxShadow: '0 4px 12px rgba(0, 0, 0, 0.08)',
  padding: 'xs',
  minWidth: '12rem',
  maxHeight: '16rem',
  overflowY: 'auto',
  zIndex: 50,
});

const itemStyles = css({
  display: 'flex',
  alignItems: 'center',
  paddingBlock: 'xs',
  paddingInline: 'sm',
  borderRadius: 'sm',
  fontFamily: 'body',
  fontSize: 'body',
  color: 'fg',
  cursor: 'pointer',
  '&[data-highlighted]': { bg: 'accentBg' },
  '&[data-state="checked"]': { fontWeight: 'bold', color: 'accent' },
  _disabled: { opacity: 0.5, cursor: 'not-allowed' },
});

export interface SelectOption<TValue extends string> {
  readonly value: TValue;
  readonly label: ReactNode;
}

export interface SelectProps<TValue extends string> {
  readonly label: ReactNode;
  // `null` means no selection — Ark requires `value: string[]`, so we
  // translate at the boundary instead of forcing callers to think in arrays.
  readonly value: TValue | null;
  readonly onValueChange: (value: TValue | null) => void;
  readonly options: readonly SelectOption<TValue>[];
  readonly placeholder?: string;
  readonly name?: string;
  readonly rootClassName?: string;
}

export function Select<TValue extends string>({
  label,
  value,
  onValueChange,
  options,
  placeholder,
  name,
  rootClassName,
}: SelectProps<TValue>) {
  const collection = useMemo(
    () =>
      createListCollection({
        items: options as ReadonlyArray<SelectOption<TValue>>,
        itemToValue: (item) => item.value,
        itemToString: (item) =>
          typeof item.label === 'string' ? item.label : item.value,
      }),
    [options],
  );

  const handleChange = (details: SelectValueChangeDetails) => {
    const next = details.value[0];
    onValueChange((next as TValue | undefined) ?? null);
  };

  return (
    <ArkSelect.Root
      collection={collection}
      value={value == null ? [] : [value]}
      onValueChange={handleChange}
      name={name}
      positioning={{ sameWidth: true }}
      className={cx(rootStyles, rootClassName)}
    >
      <ArkSelect.Label className={labelStyles}>{label}</ArkSelect.Label>
      <ArkSelect.Control>
        <ArkSelect.Trigger className={triggerStyles}>
          <ArkSelect.ValueText placeholder={placeholder} />
          <ArkSelect.Indicator className={indicatorStyles}>▾</ArkSelect.Indicator>
        </ArkSelect.Trigger>
      </ArkSelect.Control>
      <Portal>
        <ArkSelect.Positioner>
          <ArkSelect.Content className={contentStyles}>
            {options.map((option) => (
              <ArkSelect.Item key={option.value} item={option} className={itemStyles}>
                <ArkSelect.ItemText>{option.label}</ArkSelect.ItemText>
              </ArkSelect.Item>
            ))}
          </ArkSelect.Content>
        </ArkSelect.Positioner>
      </Portal>
    </ArkSelect.Root>
  );
}
