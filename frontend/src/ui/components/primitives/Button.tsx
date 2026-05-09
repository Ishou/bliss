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
//   * primary   — solid brand-tinted fill, `fg` text. Brand CTA.
//   * secondary — branded outline + transparent fill. Modal "Fermer",
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
  borderRadius: '6px',
  paddingInline: 'md',
  paddingBlock: 'sm',
  fontSize: 'body',
  fontFamily: 'body',
  fontWeight: 'bold',
  cursor: 'pointer',
  transition: 'background-color 120ms ease-out, color 120ms ease-out, opacity 120ms ease-out, border-color 120ms ease-out',
  // Shared focus ring — `focusRing` (= secondary.400, the rose) at
  // 2px / 2px offset. Brief §8 mandates a visible focus state on every
  // interactive surface; rose-on-CTA-edge keeps the affordance crisp
  // against the sage fill.
  _focusVisible: { outline: '2px solid token(colors.focusRing)', outlineOffset: '2px' },
  _disabled: { opacity: 0.5, cursor: 'not-allowed' },
});

const variantStyles = {
  // Spec primary CTA (ADR-0005 §6): solid sage with dark sage-on text,
  // no border. Hover lifts to the next-warmer sage shade rather than
  // dimming, matching the brief's "hover lifts the brightness" rule.
  primary: css({
    bg: 'accent',
    color: 'onAccent',
    border: 'none',
    _hover: { bg: 'primary.400' },
  }),
  secondary: css({
    bg: 'transparent',
    color: 'accent',
    border: '1px solid token(colors.accent)',
    _hover: { bg: 'accentBg' },
  }),
  ghost: css({
    bg: 'surface',
    color: 'fg',
    border: '1px solid token(colors.border)',
    // Hover bg is the dark sage `accentBg`, not `primary.50` (the
    // upstream WCAG-AA fix); my branch dropped the legacy
    // `fontWeight: 'semibold'` because the brand brief (ADR-0005 §3)
    // caps the type system at weights 400 / 500.
    _hover: { bg: 'accentBg' },
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
