import { css } from 'styled-system/css';
import { IconButton } from '@/ui/components/primitives';
import { RefreshIcon, SettingsIcon } from '@/ui/components/icons';
import { TimerPill } from './TimerPill';

// Toolbar above the grid — ADR-0005 §5.
//
// Three columns: timer pill on the left, grid metadata centered, icon
// buttons on the right. On mobile the settings cog is dropped (the brief
// says it moves into the menu — there is no menu in the current scope,
// so it's simply hidden); only refresh remains.
//
// `onRefresh` is wired to the parent (which currently issues a
// no-op-friendly refetch); `onOpenSettings` is invoked but the parent
// holds no settings dialog yet, so the desktop callback can no-op too.
// The buttons exist visually because the brief's mock places them; the
// surfaces they would drive are out of scope.

const toolbarStyles = css({
  display: 'grid',
  gridTemplateColumns: '1fr auto 1fr',
  alignItems: 'center',
  gap: '12px',
  width: '100%',
});

const leftSlotStyles = css({
  display: 'flex',
  justifyContent: 'flex-start',
});

const centerSlotStyles = css({
  fontFamily: 'body',
  fontSize: 'sm',
  color: 'fgMuted',
  textAlign: 'center',
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
});

const rightSlotStyles = css({
  display: 'flex',
  justifyContent: 'flex-end',
  alignItems: 'center',
  gap: '8px',
});

const desktopOnlyStyles = css({ display: { base: 'none', md: 'inline-flex' } });

export interface PuzzleToolbarProps {
  readonly metadata: string;
  readonly onRefresh?: () => void;
  readonly onOpenSettings?: () => void;
}

export function PuzzleToolbar({
  metadata,
  onRefresh,
  onOpenSettings,
}: PuzzleToolbarProps) {
  return (
    <div className={toolbarStyles} role="toolbar" aria-label="Outils de la grille">
      <div className={leftSlotStyles}>
        <TimerPill />
      </div>
      <div className={centerSlotStyles} aria-label="Informations de la grille">
        {metadata}
      </div>
      <div className={rightSlotStyles}>
        <IconButton
          aria-label="Actualiser la grille"
          onClick={onRefresh}
          disabled={onRefresh === undefined}
        >
          <RefreshIcon />
        </IconButton>
        <IconButton
          aria-label="Paramètres"
          onClick={onOpenSettings}
          disabled={onOpenSettings === undefined}
          className={desktopOnlyStyles}
        >
          <SettingsIcon />
        </IconButton>
      </div>
    </div>
  );
}
