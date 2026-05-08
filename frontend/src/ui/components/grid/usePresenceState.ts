import { useEffect, useState } from 'react';
import type { GameEvent, Unsubscribe } from '@/application/game';
import type { SessionId } from '@/domain/game';

// Per-peer presence state aggregated from the four ephemeral server events
// (`typing`, `idle`, `connectionLost`, plus implicit clearing from
// `presenceUpdated` / `cellUpdated`). Components consume this via the hook
// below; the cell-level highlight pipeline merges `typing` into each
// rendered cell's `CellPresence`, while the roster pill consumes the whole
// map for the typing dot + the `.idle` / `.disconnecting` modifiers.
//
// State machine for a session:
//   - `typing` rises on `typing(true)`, falls on `typing(false)`. The
//     server emits both edges; clients SHOULD treat dropped trailing
//     edges as a stale state (the next `typing(true)` resets it).
//   - `idle` rises on `idle(true)`, falls on `idle(false)`. Mirrors the
//     server-side timer; the frontend never derives the threshold itself.
//   - `connectionLost` rises on `connectionLost`, falls on the next
//     `presenceUpdated` for that session (or a `playerLeft`, which the
//     `players` map upstream removes regardless).
//
// Unknown sessions are tracked lazily — the first event for a session
// creates an entry; absence from the map = no signal yet.
export interface PeerPresenceState {
  readonly typing: boolean;
  readonly idle: boolean;
  readonly connectionLost: boolean;
}

const EMPTY_STATE: PeerPresenceState = {
  typing: false,
  idle: false,
  connectionLost: false,
};

type SubscribeFn = (handler: (event: GameEvent) => void) => Unsubscribe;

/**
 * Subscribes to the realtime stream and returns a `Map<SessionId, PeerPresenceState>`
 * derived from the typing / idle / connectionLost / presenceUpdated frames.
 * The local player's own session is filtered out — their state is not
 * meaningful on their own grid.
 */
export function usePresenceState(
  subscribe: SubscribeFn | undefined,
  currentSessionId: SessionId | undefined,
): ReadonlyMap<SessionId, PeerPresenceState> {
  const [state, setState] = useState<ReadonlyMap<SessionId, PeerPresenceState>>(
    () => new Map(),
  );

  useEffect(() => {
    if (!subscribe) return;
    const unsubscribe = subscribe((event) => {
      if (!isPeerEvent(event)) return;
      const sessionId = event.sessionId;
      if (currentSessionId !== undefined && sessionId === currentSessionId) {
        return;
      }
      setState((prev) => updateState(prev, event, sessionId));
    });
    return unsubscribe;
  }, [subscribe, currentSessionId]);

  return state;
}

// Narrow the event union to the variants we actually consume here.
type PeerEvent =
  | { type: 'typing'; sessionId: SessionId; typing: boolean }
  | { type: 'idle'; sessionId: SessionId; idle: boolean }
  | { type: 'connectionLost'; sessionId: SessionId }
  | {
      type: 'presenceUpdated';
      sessionId: SessionId;
      row: number | null;
      column: number | null;
      direction: 'across' | 'down' | null;
    };

function isPeerEvent(event: GameEvent): event is PeerEvent {
  return (
    event.type === 'typing' ||
    event.type === 'idle' ||
    event.type === 'connectionLost' ||
    event.type === 'presenceUpdated'
  );
}

function updateState(
  prev: ReadonlyMap<SessionId, PeerPresenceState>,
  event: PeerEvent,
  sessionId: SessionId,
): ReadonlyMap<SessionId, PeerPresenceState> {
  const existing = prev.get(sessionId) ?? EMPTY_STATE;
  const next = applyEvent(existing, event);
  if (statesEqual(existing, next)) return prev;
  const merged = new Map(prev);
  merged.set(sessionId, next);
  return merged;
}

function applyEvent(
  current: PeerPresenceState,
  event: PeerEvent,
): PeerPresenceState {
  switch (event.type) {
    case 'typing':
      return { ...current, typing: event.typing };
    case 'idle':
      return { ...current, idle: event.idle };
    case 'connectionLost':
      return { ...current, connectionLost: true };
    case 'presenceUpdated':
      // Any presence frame for this session implies the socket is alive.
      // Clear `connectionLost` so the roster pill comes back from the
      // grey-out state on reconnect activity.
      return current.connectionLost
        ? { ...current, connectionLost: false }
        : current;
  }
}

function statesEqual(a: PeerPresenceState, b: PeerPresenceState): boolean {
  return (
    a.typing === b.typing &&
    a.idle === b.idle &&
    a.connectionLost === b.connectionLost
  );
}
