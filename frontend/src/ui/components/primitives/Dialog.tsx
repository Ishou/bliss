import { Dialog as ArkDialog, type DialogOpenChangeDetails } from '@ark-ui/react/dialog';
import { Portal } from '@ark-ui/react/portal';
import type { ReactNode } from 'react';
import { css, cx } from 'styled-system/css';

// Project-local Dialog primitive — wraps Ark UI's `Dialog` so the
// hand-rolled focus-trap, ESC handler, focus-restore, role/aria-modal
// wiring and backdrop click-to-close in `EndGameModal` and any future
// modal go through one accessible implementation.
//
// Ark's `Dialog.Root` provides:
//   * `role="dialog"` + `aria-modal="true"` on the content node
//   * focus-trap (Tab cycles within content; Shift+Tab wraps backwards)
//   * ESC closes (calls `onOpenChange({ open: false })`)
//   * outside-click closes (`closeOnInteractOutside` defaults to `true`)
//   * focus moves into the dialog on open and is restored on close
//     (`restoreFocus` defaults to `true`)
//   * scroll lock + `inert` on the rest of the page when modal
//
// Visual layer (backdrop scrim, surface card, padding rhythm) stays
// owned by Bliss — the styles below come from the legacy `EndGameModal`
// declarations so the visual output is unchanged.

const backdropStyles = css({
  position: 'fixed',
  inset: 0,
  // Translucent ink scrim — keeps the page visible underneath while
  // dimming it enough for the dialog surface to read as elevated.
  bg: 'rgba(27, 40, 69, 0.6)',
  zIndex: 1000,
});

const positionerStyles = css({
  position: 'fixed',
  inset: 0,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  padding: 'md',
  zIndex: 1001,
});

const contentStyles = css({
  bg: 'cream',
  color: 'fg',
  borderRadius: 'md',
  padding: 'lg',
  maxWidth: '420px',
  width: '100%',
  boxShadow: '0 12px 40px -8px rgba(0, 0, 0, 0.35)',
  display: 'flex',
  flexDirection: 'column',
  gap: 'md',
  fontFamily: 'body',
  outline: 'none',
  _focusVisible: {
    boxShadow: '0 12px 40px -8px rgba(0, 0, 0, 0.35), 0 0 0 3px token(colors.leaf.500)',
  },
});

const titleStyles = css({
  fontSize: 'xl',
  fontWeight: 'bold',
  color: 'leaf.700',
  margin: 0,
});

const descriptionStyles = css({
  fontSize: 'body',
  color: 'fg',
  margin: 0,
  textAlign: 'center',
  opacity: 0.8,
});

export interface DialogProps {
  readonly open: boolean;
  readonly onClose: () => void;
  readonly title: ReactNode;
  // Optional accessible description; Ark wires it to `aria-describedby`.
  readonly children: ReactNode;
  // Callers occasionally need a stable test hook. Spread on the backdrop
  // node so existing assertions (e.g. `data-testid` lookup of the
  // backdrop in `end-game-modal.test.tsx`) keep passing.
  readonly backdropTestId?: string;
  readonly contentTestId?: string;
}

// `closeOnInteractOutside` covers backdrop-click + click on anything
// outside the content. `closeOnEscape` covers ESC. Both default to true
// in zag; surfaced explicitly so the contract is visible at the call
// site and easy to audit.
export function Dialog({
  open,
  onClose,
  title,
  children,
  backdropTestId,
  contentTestId,
}: DialogProps) {
  const handleOpenChange = (details: DialogOpenChangeDetails) => {
    if (!details.open) onClose();
  };

  return (
    <ArkDialog.Root
      open={open}
      onOpenChange={handleOpenChange}
      modal
      closeOnInteractOutside
      closeOnEscape
      preventScroll
      role="dialog"
    >
      <Portal>
        <ArkDialog.Backdrop
          className={backdropStyles}
          data-testid={backdropTestId}
        />
        <ArkDialog.Positioner className={positionerStyles}>
          <ArkDialog.Content
            className={cx(contentStyles)}
            data-testid={contentTestId}
          >
            <ArkDialog.Title className={titleStyles}>{title}</ArkDialog.Title>
            {children}
          </ArkDialog.Content>
        </ArkDialog.Positioner>
      </Portal>
    </ArkDialog.Root>
  );
}

// Re-export Description so call sites can opt into the
// `aria-describedby` wiring without a second import.
export const DialogDescription = ({
  children,
  className,
}: {
  readonly children: ReactNode;
  readonly className?: string;
}) => (
  <ArkDialog.Description className={cx(descriptionStyles, className)}>
    {children}
  </ArkDialog.Description>
);
