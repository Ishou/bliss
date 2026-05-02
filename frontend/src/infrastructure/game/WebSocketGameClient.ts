// Browser-WebSocket adapter implementing the application-layer `GameClient`
// port. Wire shapes mirror `game/api/asyncapi.yaml` verbatim — every payload
// carries the top-level `type` discriminator the server dispatches on, and
// the `error` frame uses `errorType` (not `type`) for the RFC 7807 URI to
// avoid colliding with that discriminator.
//
// Layering (ADR-0002 §7): only this layer touches `WebSocket` for game/.
// The composition root threads an instance through the router context.
//
// Reconnection is OUT OF SCOPE — basic open/close only. The lobby route in
// Wave G wraps this adapter and adds reconnect-with-backoff (§Reconnect in
// `game/api/asyncapi.yaml`).

import type {
  ConnectionState,
  GameClient,
  GameEvent,
  Unsubscribe,
} from '@/application/game';
import type { GridConfig, Letter, Pseudonym, SessionId } from '@/domain/game';

// Structural subset of the global `WebSocket`. The `this`-less event-handler
// signatures keep both the DOM `WebSocket` and any test mock assignable.
interface WebSocketLike {
  readyState: number;
  send(data: string): void;
  close(code?: number, reason?: string): void;
  onopen: ((ev: Event) => unknown) | null;
  onclose: ((ev: CloseEvent) => unknown) | null;
  onerror: ((ev: Event) => unknown) | null;
  onmessage: ((ev: MessageEvent) => unknown) | null;
}

type WebSocketCtor = new (url: string) => WebSocket;

// `WebSocket.OPEN` is `1` in every spec-conformant impl; held locally so
// the module doesn't require the DOM lib at import time.
const WS_OPEN = 1;

// Server→client `type` literals from the AsyncAPI subscribe operation.
// Anything outside this allow-list is dropped with `console.warn` so
// new server messages stay forward-compat (clients SHOULD ignore unknown
// types per the spec's subscribe description).
const SERVER_EVENT_TYPES = new Set<GameEvent['type']>([
  'lobbyState', 'playerJoined', 'playerLeft', 'playerRenamed',
  'gameStarted', 'cellUpdated', 'gameSolved', 'error',
]);

export interface WebSocketGameClientOptions {
  // Base WebSocket URL, e.g. `wss://game.wordsparrow.io`. The lobby path
  // (`/v1/lobbies/{lobbyId}/ws`) is appended on `connect`.
  readonly wsBaseUrl: string;
  // Optional ctor injection for tests. Defaults to `globalThis.WebSocket`.
  readonly WebSocketCtor?: WebSocketCtor;
}

export function createWebSocketGameClient(
  options: WebSocketGameClientOptions,
): GameClient {
  const Ctor: WebSocketCtor =
    options.WebSocketCtor ??
    (globalThis as { WebSocket?: WebSocketCtor }).WebSocket!;
  if (!Ctor) {
    throw new Error('WebSocketGameClient: no WebSocket constructor available');
  }

  let socket: WebSocketLike | null = null;
  let pendingJoin: { sessionId: SessionId; pseudonym: Pseudonym } | null = null;
  const subscribers = new Set<(event: GameEvent) => void>();
  // Transport-lifecycle subscribers (Wave H PR #17, `ConnectionBanner`).
  // `disconnected` is the initial state — `connect()` flips it to
  // `connecting`, `onopen` to `connected`, and any `onclose` back to
  // `disconnected`. The adapter does not emit `reconnecting`; that
  // state is reserved for a higher-level reconnect-with-backoff
  // wrapper (still out of scope per the file-level note above), which
  // can either subscribe through this same channel or expose its own.
  let connectionState: ConnectionState = 'disconnected';
  const connectionSubscribers = new Set<(state: ConnectionState) => void>();
  const setConnectionState = (next: ConnectionState): void => {
    if (connectionState === next) return;
    connectionState = next;
    // Snapshot subscribers so a handler that detaches mid-dispatch
    // doesn't skip a sibling.
    for (const handler of [...connectionSubscribers]) handler(next);
  };

  const sendFrame = (frame: ClientToServerFrame): void => {
    if (!socket || socket.readyState !== WS_OPEN) {
      throw new Error('WebSocketGameClient: socket is not open');
    }
    socket.send(JSON.stringify(frame));
  };

  const handleMessage = (raw: unknown): void => {
    if (typeof raw !== 'string') return;
    let parsed: unknown;
    try { parsed = JSON.parse(raw); }
    catch {
      console.warn('WebSocketGameClient: dropping non-JSON frame');
      return;
    }
    if (!parsed || typeof parsed !== 'object' || !('type' in parsed)) {
      console.warn('WebSocketGameClient: dropping frame without `type`');
      return;
    }
    const type = (parsed as { type: unknown }).type;
    if (typeof type !== 'string' || !SERVER_EVENT_TYPES.has(type as GameEvent['type'])) {
      console.warn(`WebSocketGameClient: dropping unknown frame type "${String(type)}"`);
      return;
    }
    // Snapshot subscribers so a handler that detaches mid-dispatch
    // doesn't skip a sibling.
    for (const handler of [...subscribers]) handler(parsed as GameEvent);
  };

  return {
    connect({ lobbyId, sessionId, pseudonym }) {
      if (socket) throw new Error('WebSocketGameClient: already connected');
      const ws = new Ctor(`${options.wsBaseUrl}/v1/lobbies/${lobbyId}/ws`) as unknown as WebSocketLike;
      socket = ws;
      pendingJoin = { sessionId, pseudonym };
      setConnectionState('connecting');

      return new Promise<void>((resolve, reject) => {
        let settled = false;
        ws.onopen = () => {
          settled = true;
          // Send handshake immediately on open. AsyncAPI `JoinLobby`
          // carries sessionId + pseudonym in the payload; the URL never
          // does (so the session token never lands in proxy logs).
          if (pendingJoin) {
            ws.send(JSON.stringify({
              type: 'joinLobby',
              sessionId: pendingJoin.sessionId,
              pseudonym: pendingJoin.pseudonym,
            } satisfies JoinLobbyFrame));
            pendingJoin = null;
          }
          setConnectionState('connected');
          resolve();
        };
        ws.onerror = () => {
          if (!settled) {
            settled = true;
            reject(new Error('WebSocketGameClient: connect failed'));
          }
          // Post-open errors flow through close + the spec's structured
          // `error` server frame; we don't synthesize a GameEvent here.
        };
        ws.onclose = () => {
          socket = null;
          pendingJoin = null;
          // Any close — clean or not — drops the transport. A future
          // reconnect wrapper will flip this to `reconnecting` before
          // the next `connect()` attempt.
          setConnectionState('disconnected');
        };
        ws.onmessage = (event) => handleMessage(event.data);
      });
    },

    joinLobby() {
      // Handshake is auto-sent on `open`. This is a no-op when already
      // sent — exposed on the port for reconnect-wrapper symmetry.
      if (!pendingJoin) return;
      sendFrame({
        type: 'joinLobby',
        sessionId: pendingJoin.sessionId,
        pseudonym: pendingJoin.pseudonym,
      });
      pendingJoin = null;
    },

    renameSelf(pseudonym) {
      sendFrame({ type: 'renameSelf', newPseudonym: pseudonym });
    },

    setGridConfig(config: GridConfig) {
      sendFrame({ type: 'setGridConfig', width: config.width, height: config.height });
    },

    startGame() { sendFrame({ type: 'startGame' }); },

    cellUpdate(row, column, letter: Letter | null) {
      sendFrame({ type: 'cellUpdate', row, column, letter });
    },

    leaveLobby() { sendFrame({ type: 'leaveLobby' }); },

    disconnect() {
      const ws = socket;
      if (!ws) return;
      // Normal closure per RFC 6455. The server keeps the slot warm for
      // ~30s by sessionId so reconnects don't kick the player.
      ws.close(1000);
      socket = null;
      pendingJoin = null;
    },

    subscribe(handler): Unsubscribe {
      subscribers.add(handler);
      return () => { subscribers.delete(handler); };
    },

    subscribeConnectionState(handler): Unsubscribe {
      connectionSubscribers.add(handler);
      // Synchronous priming call — a freshly-mounted banner reads the
      // current state immediately rather than rendering its initial
      // chrome until the next transition fires.
      handler(connectionState);
      return () => { connectionSubscribers.delete(handler); };
    },
  };
}

// ----- Client→server frame shapes (AsyncAPI client→server payloads) -----

interface JoinLobbyFrame {
  readonly type: 'joinLobby';
  readonly sessionId: SessionId;
  readonly pseudonym: Pseudonym;
}
interface RenameSelfFrame {
  readonly type: 'renameSelf';
  readonly newPseudonym: Pseudonym;
}
interface SetGridConfigFrame {
  readonly type: 'setGridConfig';
  readonly width: number;
  readonly height: number;
}
interface StartGameFrame { readonly type: 'startGame'; }
interface CellUpdateFrame {
  readonly type: 'cellUpdate';
  readonly row: number;
  readonly column: number;
  readonly letter: Letter | null;
}
interface LeaveLobbyFrame { readonly type: 'leaveLobby'; }

type ClientToServerFrame =
  | JoinLobbyFrame | RenameSelfFrame | SetGridConfigFrame
  | StartGameFrame | CellUpdateFrame | LeaveLobbyFrame;
