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
  // Optional server-driven timer parameters. Multiplayer passes
  // `timerStartedAt` (ISO string from `gameStarted.startedAt`) so the
  // pill ticks against the server clock; `timerFrozenAtMs` freezes
  // the display once the game ends. Solo omits both — `TimerPill`
  // falls back to its mount-instant tick.
  readonly timerStartedAt?: string;
  readonly timerFrozenAtMs?: number;
  // Optional slot rendered before the refresh icon. Solo passes the
  // hint affordance (`<HintControl />`); multiplayer omits it — the
  // hint endpoint is solo-scoped per ADR-0018.
  readonly hintSlot?: React.ReactNode;
}

export function PuzzleToolbar({
  metadata,
  onRefresh,
  onOpenSettings,
  timerStartedAt,
  timerFrozenAtMs,
  hintSlot,
}: PuzzleToolbarProps) {
  return (
    <div className={toolbarStyles} role="toolbar" aria-label="Outils de la grille">
      <div className={leftSlotStyles}>
        <TimerPill startedAt={timerStartedAt} frozenAtMs={timerFrozenAtMs} />
      </div>
      <div className={centerSlotStyles} aria-label="Informations de la grille">
        {metadata}
      </div>
      <div className={rightSlotStyles}>
        {hintSlot}
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
