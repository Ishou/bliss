import { type ReactNode, useCallback, type KeyboardEvent, type MouseEvent, type PointerEvent } from 'react';
import { css } from 'styled-system/css';

const keyBase = css({
  flex: 1,
  minWidth: 0,
  minHeight: '44px',
  paddingBlock: '4px',
  paddingInline: '4px',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  borderRadius: '7px',
  border: '1px solid token(colors.border)',
  bg: 'surface',
  color: 'fg',
  fontFamily: 'body',
  fontWeight: 'medium',
  fontSize: '16px',
  cursor: 'pointer',
  touchAction: 'manipulation',
  boxShadow: '0 1px 0 rgba(0,0,0,0.06)',
  transition: 'transform 80ms ease-out, background-color 80ms ease-out',
  _active: { transform: 'scale(0.96)', bg: 'surfaceElevated' },
  _focusVisible: { outline: '2px solid token(colors.focusRing)', outlineOffset: '2px' },
  _disabled: { opacity: 0.5, cursor: 'not-allowed' },
});

const keyAction = css({
  bg: 'surfaceElevated',
  fontSize: '13px',
  color: 'fgMuted',
});

export interface KeyboardKeyProps {
  readonly label: ReactNode;
  readonly ariaLabel: string;
  readonly onPress: () => void;
  readonly disabled?: boolean;
  readonly variant?: 'letter' | 'action';
}

export function KeyboardKey({
  label,
  ariaLabel,
  onPress,
  disabled = false,
  variant = 'letter',
}: KeyboardKeyProps) {
  // Primary-button press only; synthesized click has no handler to land in.
  const handlePointerDown = useCallback(
    (e: PointerEvent<HTMLButtonElement>) => {
      if (e.button !== 0 || disabled) return;
      e.preventDefault();
      onPress();
    },
    [disabled, onPress],
  );

  // Enter/Space keyboard activation; preventDefault stops page scroll on Space.
  const handleKeyDown = useCallback(
    (e: KeyboardEvent<HTMLButtonElement>) => {
      if (disabled) return;
      if (e.key !== 'Enter' && e.key !== ' ') return;
      e.preventDefault();
      onPress();
    },
    [disabled, onPress],
  );

  // Suppress the long-press context menu on touch (interrupts rapid typing).
  const handleContextMenu = useCallback((e: MouseEvent) => {
    e.preventDefault();
  }, []);

  return (
    <button
      type="button"
      className={variant === 'action' ? `${keyBase} ${keyAction}` : keyBase}
      aria-label={ariaLabel}
      aria-disabled={disabled || undefined}
      onPointerDown={handlePointerDown}
      onKeyDown={handleKeyDown}
      onContextMenu={handleContextMenu}
    >
      {label}
    </button>
  );
}
