import { forwardRef } from 'react';
import { css, cx } from 'styled-system/css';

// Project-local Button primitive — ADR-0002 §2.
//
// Ark UI is *headless* and intentionally does not ship a Button: a native
// `<button>` already exposes the right ARIA semantics, focus behaviour,
// and form-submit wiring. The primitive's job is therefore to centralise
// the brand styling (Panda tokens) and a small variant surface so call
// sites stop hand-rolling the same `paddingInline` / `borderRadius` /
// `_hover` rhythm in every component.
//
// Variants map to the existing ad-hoc styles found in `EndGameModal`,
// `WaitingRoom`, and `routes/index.tsx`:
//
//   * primary   — solid `leaf.800` fill, `petal` text. Brand CTA.
//   * secondary — `leaf.700` outline + transparent fill. Modal "Fermer",
//                 the WaitingRoom share button.
//   * ghost     — minimal styling for affordances like the inline
//                 pseudonym-edit trigger; visible border but `surface`
//                 fill so it reads as "inert until interacted with".
//
// `type` defaults to `'button'` — matches the previous call sites and
// avoids the well-known "submit by accident inside a form" footgun.

const baseStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  borderRadius: 'sm',
  paddingInline: 'md',
  paddingBlock: 'sm',
  fontSize: 'body',
  fontFamily: 'body',
  fontWeight: 'bold',
  cursor: 'pointer',
  // Shared focus ring — `leaf.500` at 3px / 2px offset matches the
  // legacy modal focus ring so screen readers + keyboard users see the
  // same affordance everywhere.
  _focusVisible: { outline: '3px solid token(colors.leaf.500)', outlineOffset: '2px' },
  _disabled: { opacity: 0.5, cursor: 'not-allowed' },
});

const variantStyles = {
  primary: css({
    bg: 'leaf.800',
    color: 'petal',
    border: 'none',
    _hover: { bg: 'leaf.900' },
  }),
  secondary: css({
    bg: 'transparent',
    color: 'leaf.700',
    border: '1px solid token(colors.leaf.700)',
    _hover: { bg: 'leaf.50' },
  }),
  ghost: css({
    bg: 'surface',
    color: 'fg',
    border: '1px solid token(colors.border)',
    fontWeight: 'semibold',
    _hover: { bg: 'leaf.50' },
  }),
} as const;

export type ButtonVariant = keyof typeof variantStyles;

export interface ButtonProps
  extends Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, 'type'> {
  readonly variant?: ButtonVariant;
  readonly type?: 'button' | 'submit' | 'reset';
}

// `forwardRef` so call sites that need a ref (focus management,
// integration with Ark's `initialFocusEl`) keep working.
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'primary', type = 'button', className, ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      className={cx(baseStyles, variantStyles[variant], className)}
      {...rest}
    />
  );
});
