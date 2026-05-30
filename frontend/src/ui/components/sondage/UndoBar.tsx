import { css } from 'styled-system/css';

const barStyles = css({
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 'sm',
  bg: 'surfaceMuted',
  border: '1px solid token(colors.border)',
  borderRadius: 'md',
  paddingInline: 'md',
  paddingBlock: 'sm',
});

const labelStyles = css({
  fontSize: 'sm',
  color: 'fgMuted',
  margin: 0,
});

const buttonStyles = css({
  minHeight: '44px',
  paddingInline: 'md',
  fontSize: 'sm',
  fontWeight: 'semibold',
  color: 'accent',
  bg: 'surface',
  border: '1px solid token(colors.accent)',
  borderRadius: 'sm',
  cursor: 'pointer',
  _hover: { bg: 'surfaceMuted' },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
  _disabled: { opacity: 0.5, cursor: 'not-allowed' },
});

export interface UndoBarProps {
  readonly onUndo: () => void;
  readonly busy?: boolean;
}

export function UndoBar({ onUndo, busy = false }: UndoBarProps) {
  return (
    <div className={barStyles} data-testid="undo-bar">
      <p className={labelStyles} role="status">Action enregistrée.</p>
      <button
        type="button"
        className={buttonStyles}
        onClick={onUndo}
        disabled={busy}
        data-testid="undo-button"
      >
        Annuler
      </button>
    </div>
  );
}
