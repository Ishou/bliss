import { Field } from '@ark-ui/react/field';
import { forwardRef } from 'react';
import { css, cx } from 'styled-system/css';

// Project-local TextField primitive — wraps Ark UI's `Field` so the
// label / input / error-text / aria-describedby wiring is handled once
// and call sites stop hand-rolling `htmlFor` ↔ `id` matches.
//
// Visual layer is owned by Bliss (ADR-0002 §2): the underlying Field
// parts are headless; the styles below come straight from the legacy
// hand-rolled `WaitingRoom` `input` / `sectionTitle` declarations so
// the migration is structural and the rendered pixels are unchanged.

const rootStyles = css({
  display: 'flex',
  flexDirection: 'column',
  gap: 'xs',
});

const labelStyles = css({
  fontSize: 'md',
  fontWeight: 'bold',
  color: 'leaf.700',
  margin: 0,
});

const inputStyles = css({
  flex: 1,
  paddingBlock: 'sm',
  paddingInline: 'sm',
  borderRadius: 'sm',
  border: '1px solid token(colors.border)',
  bg: 'surface',
  color: 'fg',
  fontFamily: 'body',
  fontSize: 'body',
  _focusVisible: {
    outline: '3px solid token(colors.leaf.500)',
    outlineOffset: '2px',
  },
});

const errorStyles = css({
  fontSize: 'sm',
  color: 'blossom.700',
  margin: 0,
});

export interface TextFieldProps
  extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'type'> {
  readonly label: string;
  readonly errorText?: string;
  readonly invalid?: boolean;
  readonly type?: 'text' | 'email' | 'password' | 'search' | 'tel' | 'url';
  // Callers occasionally need to override the visual layout (e.g. an
  // inline row layout in WaitingRoom's pseudonym editor). Surface the
  // wrapping div's className without losing the default flex-col styling.
  readonly rootClassName?: string;
}

export const TextField = forwardRef<HTMLInputElement, TextFieldProps>(function TextField(
  { label, errorText, invalid, type = 'text', rootClassName, className, ...inputProps },
  ref,
) {
  return (
    <Field.Root invalid={invalid} className={cx(rootStyles, rootClassName)}>
      <Field.Label className={labelStyles}>{label}</Field.Label>
      <Field.Input
        ref={ref}
        type={type}
        className={cx(inputStyles, className)}
        {...inputProps}
      />
      {errorText != null ? (
        <Field.ErrorText className={errorStyles} role="alert">{errorText}</Field.ErrorText>
      ) : null}
    </Field.Root>
  );
});
