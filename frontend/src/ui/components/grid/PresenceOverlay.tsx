import { useEffect, useState } from 'react';
import type {
  GameEvent,
  PresenceUpdatedEvent,
  Unsubscribe,
} from '@/application/game';
import type { Player, SessionId } from '@/domain/game';
import type { Position, Puzzle } from '@/domain';
import { playerColorVars, playerInitial } from '@/ui/lib/playerColor';
import { wordRange } from './wordRange';
import type { CellPresence } from './Cell';

// Multiplayer presence — state computer for the per-cell visual treatment.
// ADR-0018 §"Presence" promised distinct stable colours per peer. The
// previous implementation rendered a separate overlay layer with rings
// and pseudonym chips; the new design pushes the visuals into each cell
// (active ring, word tint, badge) so the validated-cell sage override has
// a single owner — `Cell.tsx` — and there's no `mix-blend-mode` stacking.
//
// This module owns the data, not the JSX:
//   - `useRemotePresences` subscribes to `presenceUpdated` events and
//     returns an arrival-ordered list of remote presence states.
//   - `buildCellPresenceMap` resolves per-cell `{ sessionId, role, badge }`
//     entries combining the local cursor and remote presences. Most-
//     recent-wins on overlap for the word role; active role tracks the
//     single player whose cursor is on the cell. Validated cells are
//     subtracted upstream via the `validatedPositions` set.

interface RemotePresenceState {
  readonly sessionId: SessionId;
  readonly position: Position;
  readonly direction: 'across' | 'down';
}

// Subscribe-style registrar handed by the lobby route.
type SubscribeFn = (handler: (event: GameEvent) => void) => Unsubscribe;

// React hook: subscribes to `presenceUpdated` events, drops the local
// player's own frames, and returns the arrival-ordered list of remote
// presences. Re-rendered on every relevant event so callers can pipe the
// list through `buildCellPresenceMap` per render.
export function useRemotePresences(
  subscribe: SubscribeFn | undefined,
  currentSessionId: SessionId | undefined,
): readonly RemotePresenceState[] {
  const [presences, setPresences] = useState<readonly RemotePresenceState[]>(
    [],
  );
  useEffect(() => {
    if (!subscribe) return;
    const unsubscribe = subscribe((event) => {
      if (event.type !== 'presenceUpdated') return;
      if (currentSessionId !== undefined && event.sessionId === currentSessionId) {
        return;
      }
      applyPresenceUpdate(event, setPresences);
    });
    return unsubscribe;
  }, [subscribe, currentSessionId]);
  return presences;
}

// Pure builder — composes the per-cell render plan from the local cursor
// + remote presences + validation set.
//
// Resolution rules:
//   1. Validated cells get NO presence (sage override; subtracted here).
//   2. Active role: the cell exactly matches some presence's cursor cell.
//      Local cursor takes priority over a remote whose cursor lands on
//      the same cell — the badge for the remote still renders, so the
//      remote presence isn't lost (handled at the active-cell branch).
//      Among remotes, most-recent wins (last-arrival in `presences`).
//   3. Word role: the cell falls inside some presence's word range.
//      Local cursor's word always wins on its own cells (the local user
//      is centred on their grid). Among remotes, most-recent wins.
//
// Returns a map keyed by `"row,col"` for O(1) lookup at the cell render
// site.
export function buildCellPresenceMap(args: {
  readonly puzzle: Puzzle;
  readonly remotePresences: readonly RemotePresenceState[];
  readonly localCursor: {
    readonly sessionId: SessionId;
    readonly position: Position;
    readonly direction: 'across' | 'down';
  } | null;
  readonly playersBySessionId: ReadonlyMap<SessionId, Player>;
  readonly currentSessionId: SessionId | undefined;
  readonly validatedPositions: ReadonlySet<string>;
  /**
   * Optional set of sessionIds whose typing pulse should animate. When a
   * sessionId is in this set, the badge on that peer's active cell is
   * marked `typing=true` so the keyframe activates. Local sessions and
   * sessions not currently active on a cell are unaffected.
   */
  readonly typingSessionIds?: ReadonlySet<SessionId>;
}): Map<string, CellPresence> {
  const {
    puzzle,
    remotePresences,
    localCursor,
    playersBySessionId,
    currentSessionId,
    validatedPositions,
    typingSessionIds,
  } = args;

  const result = new Map<string, CellPresence>();
  const cellKey = (p: Position) => `${p.row},${p.col}`;

  // Resolve an order-stable list of (sessionId → vars) so we don't
  // recompute the same hsl strings for every cell.
  const varsBySession = new Map<SessionId, Record<string, string>>();
  const initialBySession = new Map<SessionId, string>();
  const ensureVars = (sessionId: SessionId) => {
    let vars = varsBySession.get(sessionId);
    if (!vars) {
      vars = playerColorVars(sessionId);
      varsBySession.set(sessionId, vars);
    }
    return vars;
  };
  const ensureInitial = (sessionId: SessionId) => {
    let init = initialBySession.get(sessionId);
    if (init === undefined) {
      const player = playersBySessionId.get(sessionId);
      init = player ? playerInitial(player.pseudonym) : '?';
      initialBySession.set(sessionId, init);
    }
    return init;
  };

  // Helper: assign a presence to a cell, but never overwrite an 'active'
  // entry with a 'word' entry — active wins per cell.
  const apply = (
    pos: Position,
    sessionId: SessionId,
    role: 'active' | 'word',
    badge: string | undefined,
  ) => {
    const key = cellKey(pos);
    if (validatedPositions.has(key)) return;
    const existing = result.get(key);
    if (existing && existing.role === 'active' && role === 'word') return;
    const typing =
      role === 'active' && typingSessionIds?.has(sessionId) === true
        ? true
        : undefined;
    result.set(key, { vars: ensureVars(sessionId), role, badge, typing });
  };

  // Step 1: layer remote presences in arrival order. Word cells first,
  // then active cells — so a later remote's active cell can land on top
  // of an earlier remote's word tint within the same pass.
  for (const p of remotePresences) {
    if (!playersBySessionId.has(p.sessionId)) continue;
    const range = wordRange(puzzle, p.position, p.direction);
    for (const pos of range) {
      if (pos.row === p.position.row && pos.col === p.position.col) continue;
      apply(pos, p.sessionId, 'word', undefined);
    }
  }
  for (const p of remotePresences) {
    if (!playersBySessionId.has(p.sessionId)) continue;
    const isLocal =
      currentSessionId !== undefined && p.sessionId === currentSessionId;
    apply(
      p.position,
      p.sessionId,
      'active',
      isLocal ? undefined : ensureInitial(p.sessionId),
    );
  }

  // Step 2: layer the local cursor on top. Local always wins on cells it
  // touches — the user expects their own cursor + word tint to be the
  // visual anchor on their own grid.
  if (localCursor) {
    const range = wordRange(
      puzzle,
      localCursor.position,
      localCursor.direction,
    );
    for (const pos of range) {
      if (
        pos.row === localCursor.position.row &&
        pos.col === localCursor.position.col
      ) {
        continue;
      }
      const key = cellKey(pos);
      if (validatedPositions.has(key)) continue;
      // Local word never overwrites an active cell from a remote — that
      // remote's cursor is more important than the local word tint.
      const existing = result.get(key);
      if (existing && existing.role === 'active') continue;
      result.set(key, {
        vars: ensureVars(localCursor.sessionId),
        role: 'word',
        badge: undefined,
      });
    }
    const activeKey = cellKey(localCursor.position);
    if (!validatedPositions.has(activeKey)) {
      result.set(activeKey, {
        vars: ensureVars(localCursor.sessionId),
        role: 'active',
        badge: undefined,
      });
    }
  }

  return result;
}

// Reducer for incoming `presenceUpdated` frames. Splits out so the
// overlap dedupe logic stays unit-testable.
function applyPresenceUpdate(
  event: PresenceUpdatedEvent,
  setPresences: React.Dispatch<
    React.SetStateAction<readonly RemotePresenceState[]>
  >,
): void {
  setPresences((current) => {
    const next: RemotePresenceState[] = [];
    let replaced = false;
    for (const p of current) {
      if (p.sessionId === event.sessionId) {
        replaced = true;
        if (
          event.row !== null &&
          event.column !== null &&
          event.direction !== null
        ) {
          next.push({
            sessionId: event.sessionId,
            position: { row: event.row, col: event.column },
            direction: event.direction,
          });
        }
      } else {
        next.push(p);
      }
    }
    if (
      !replaced &&
      event.row !== null &&
      event.column !== null &&
      event.direction !== null
    ) {
      next.push({
        sessionId: event.sessionId,
        position: { row: event.row, col: event.column },
        direction: event.direction,
      });
    }
    return next;
  });
}
