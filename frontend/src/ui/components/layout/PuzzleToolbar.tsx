import { css } from 'styled-system/css';
import { IconButton, OverflowMenu } from '@/ui/components/primitives';
import {
  RefreshIcon,
  RestartIcon,
  SettingsIcon,
} from '@/ui/components/icons';
import { TimerPill } from './TimerPill';

// Toolbar above the grid — ADR-0005 §5.
//
// Three columns: timer pill on the left, grid metadata centred, action
// chrome on the right. Desktop keeps refresh + settings as bare icon
// buttons; mobile collapses both into a 3-dot overflow menu (Ark UI
// `Menu`) so the row stays one phone-thumb wide.
//
// `metadata` accepts either a plain string (used as-is on both
// breakpoints) or `{ short, full }` so callers can supply a compact
// label for narrow viewports — the mockup uses "n°142" on mobile and
// "Grille du jour · n°142 · facile" on desktop.

// touchAction: 'none' — keyboard-mounted exception to ADR-0016 §3 (see 2026-05-22 amendment).
const toolbarStyles = css({
  display: 'grid',
  gridTemplateColumns: '1fr auto 1fr',
  alignItems: 'center',
  gap: '12px',
  width: '100%',
  touchAction: 'none',
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

const metadataShortStyles = css({ display: { base: 'inline', md: 'none' } });
const metadataFullStyles = css({ display: { base: 'none', md: 'inline' } });

const rightSlotStyles = css({
  display: 'flex',
  justifyContent: 'flex-end',
  alignItems: 'center',
  gap: '8px',
});

const desktopOnlyStyles = css({ display: { base: 'none', md: 'inline-flex' } });
const mobileOnlyStyles = css({ display: { base: 'inline-flex', md: 'none' } });

export type PuzzleToolbarMetadata =
  | string
  | { readonly short: string; readonly full: string };

export interface PuzzleToolbarProps {
  readonly metadata: PuzzleToolbarMetadata;
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
  const { short, full } = normalizeMetadata(metadata);
  const overflowItems = [
    {
      id: 'refresh',
      label: 'Recommencer',
      icon: <RestartIcon />,
      onSelect: () => onRefresh?.(),
      disabled: onRefresh === undefined,
    },
    {
      id: 'settings',
      label: 'Réglages',
      icon: <SettingsIcon />,
      onSelect: () => onOpenSettings?.(),
      disabled: onOpenSettings === undefined,
    },
  ];
  return (
    <div className={toolbarStyles} role="toolbar" aria-label="Outils de la grille">
      <div className={leftSlotStyles}>
        <TimerPill startedAt={timerStartedAt} frozenAtMs={timerFrozenAtMs} />
      </div>
      <div className={centerSlotStyles} aria-label="Informations de la grille">
        {short === full ? (
          <span>{full}</span>
        ) : (
          <>
            <span className={metadataShortStyles}>{short}</span>
            <span className={metadataFullStyles}>{full}</span>
          </>
        )}
      </div>
      <div className={rightSlotStyles}>
        {hintSlot}
        <IconButton
          aria-label="Actualiser la grille"
          title="Actualiser la grille"
          onClick={onRefresh}
          disabled={onRefresh === undefined}
          className={desktopOnlyStyles}
        >
          <RefreshIcon />
        </IconButton>
        <IconButton
          aria-label="Paramètres"
          title="Paramètres"
          onClick={onOpenSettings}
          disabled={onOpenSettings === undefined}
          className={desktopOnlyStyles}
        >
          <SettingsIcon />
        </IconButton>
        <span className={mobileOnlyStyles}>
          <OverflowMenu triggerLabel="Plus d'actions" items={overflowItems} />
        </span>
      </div>
    </div>
  );
}

function normalizeMetadata(metadata: PuzzleToolbarMetadata): {
  short: string;
  full: string;
} {
  if (typeof metadata === 'string') return { short: metadata, full: metadata };
  return metadata;
}
