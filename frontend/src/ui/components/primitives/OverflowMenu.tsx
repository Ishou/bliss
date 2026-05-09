import { Menu, type MenuSelectionDetails } from '@ark-ui/react/menu';
import { Portal } from '@ark-ui/react/portal';
import type { ReactNode } from 'react';
import { css } from 'styled-system/css';
import { IconButton } from './IconButton';
import { MoreIcon } from '@/ui/components/icons';

// Three-dot overflow menu — wraps Ark UI's `Menu` so the keyboard /
// focus / outside-click semantics come from one accessible
// implementation. Visual layer (surface, padding, hover row) stays
// owned by Bliss, mirroring the `Dialog` wrapper next door.
//
// The trigger is rendered via `asChild` on top of the project
// `IconButton` primitive so the toolbar reads as a uniform set of
// 32/36-px chips. Item icons inherit colour through `currentColor`
// (the row sets a single `color` token).

const positionerStyles = css({
  zIndex: 1100,
});

const contentStyles = css({
  bg: 'surface',
  color: 'fg',
  border: '1px solid token(colors.border)',
  borderRadius: 'md',
  padding: '4px',
  minWidth: '180px',
  boxShadow: '0 12px 32px -10px rgba(0, 0, 0, 0.45)',
  display: 'flex',
  flexDirection: 'column',
  gap: '2px',
  fontFamily: 'body',
  fontSize: 'sm',
  outline: 'none',
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
});

const itemStyles = css({
  display: 'flex',
  alignItems: 'center',
  gap: '10px',
  paddingInline: '10px',
  paddingBlock: '8px',
  borderRadius: 'sm',
  color: 'fg',
  cursor: 'pointer',
  userSelect: 'none',
  transition: 'background-color 100ms ease-out, color 100ms ease-out',
  '&[data-highlighted]': {
    bg: 'color-mix(in srgb, token(colors.accent) 14%, transparent)',
    color: 'accent',
  },
  '&[data-disabled]': {
    opacity: 0.5,
    cursor: 'not-allowed',
  },
  '& svg': {
    width: '16px',
    height: '16px',
    flexShrink: 0,
  },
});

export interface OverflowMenuItem {
  readonly id: string;
  readonly label: string;
  readonly icon?: ReactNode;
  readonly onSelect: () => void;
  readonly disabled?: boolean;
}

export interface OverflowMenuProps {
  readonly triggerLabel: string;
  readonly items: ReadonlyArray<OverflowMenuItem>;
  readonly className?: string;
  /**
   * Optional override for the trigger glyph. Defaults to the three-dot
   * `MoreIcon`; the AppHeader mobile menu passes a `HamburgerIcon`.
   */
  readonly triggerIcon?: ReactNode;
}

export function OverflowMenu({
  triggerLabel,
  items,
  className,
  triggerIcon,
}: OverflowMenuProps) {
  const handleSelect = (details: MenuSelectionDetails) => {
    const item = items.find((entry) => entry.id === details.value);
    if (!item || item.disabled) return;
    item.onSelect();
  };

  return (
    <Menu.Root onSelect={handleSelect} positioning={{ placement: 'bottom-end', gutter: 6 }}>
      <Menu.Trigger asChild>
        <IconButton aria-label={triggerLabel} title={triggerLabel} className={className}>
          {triggerIcon ?? <MoreIcon />}
        </IconButton>
      </Menu.Trigger>
      <Portal>
        <Menu.Positioner className={positionerStyles}>
          <Menu.Content className={contentStyles}>
            {items.map((item) => (
              <Menu.Item
                key={item.id}
                value={item.id}
                disabled={item.disabled}
                className={itemStyles}
              >
                {item.icon}
                <span>{item.label}</span>
              </Menu.Item>
            ))}
          </Menu.Content>
        </Menu.Positioner>
      </Portal>
    </Menu.Root>
  );
}
