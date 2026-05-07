import { forwardRef } from 'react';
import { css, cx } from 'styled-system/css';

// Square icon button — ADR-0002 §2 + ADR-0005 §6.
//
// 28×28 desktop / 24×24 mobile (the brief sizes the toolbar's hint /
// refresh / settings buttons exactly here). Subtle border in the line
// colour, muted icon, hover brightens, press scales to 96%. Brand
// consistency comes from the optional `tone="accent"` variant which the
// hint button uses (sage icon over the same neutral chrome).
//
// Ark UI does not ship an `IconButton` (same rationale as `Button` —
// headless library, native `<button>` already exposes the right ARIA
// surface). The primitive's job is the brand styling and the strict
// "icon-only buttons need an aria-label" prop contract.

const baseStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  // 28 px desktop / 24 px mobile, per the brief.
  width: { base: '24px', md: '28px' },
  height: { base: '24px', md: '28px' },
  borderRadius: 'sm',
  bg: 'transparent',
  border: '1px solid token(colors.border)',
  cursor: 'pointer',
  transition: 'background-color 120ms ease-out, border-color 120ms ease-out, transform 120ms ease-out, color 120ms ease-out',
  // The icon inherits the foreground colour through `currentColor`; the
  // `tone` variants below repaint that single hue.
  _hover: { borderColor: 'fg', color: 'fg' },
  _active: { transform: 'scale(0.96)' },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
  _disabled: { opacity: 0.5, cursor: 'not-allowed' },
  // Children (icons) sized via 1em so callers can override per-instance.
  '& svg': {
    width: '1em',
    height: '1em',
    fontSize: { base: '14px', md: '16px' },
  },
});

const toneStyles = {
  muted: css({ color: 'fgMuted' }),
  accent: css({ color: 'accent' }),
} as const;

export type IconButtonTone = keyof typeof toneStyles;

export interface IconButtonProps
  extends Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, 'type' | 'aria-label'> {
  // `aria-label` is required — icon-only buttons have no visible text.
  // The brief calls out French labels in §8 (accessibility).
  readonly 'aria-label': string;
  readonly tone?: IconButtonTone;
  readonly type?: 'button' | 'submit' | 'reset';
}

export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(
  function IconButton({ tone = 'muted', type = 'button', className, ...rest }, ref) {
    return (
      <button
        ref={ref}
        type={type}
        className={cx(baseStyles, toneStyles[tone], className)}
        {...rest}
      />
    );
  },
);
