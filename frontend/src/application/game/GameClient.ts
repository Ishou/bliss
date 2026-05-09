import type {
  CellEntry,
  GamePuzzle,
  GridConfig,
  Instant,
  Letter,
  LobbyId,
  LobbyLifecycleState,
  Player,
  Position,
  Pseudonym,
  GameSession,
  SessionId,
} from '@/domain/game';

// Application-layer port for the multiplayer WebSocket client. The
// concrete adapter (Wave E PR #13's `WebSocketGameClient`) lives in
// `infrastructure/`; routes receive an instance through the TanStack
// Router context so `ui/` keeps zero `infrastructure/` imports per
// ADR-0002 §7.
//
// Method shape mirrors the AsyncAPI client→server message catalog
// (game/api/asyncapi.yaml). Inbound (server→client) frames flow through
// `subscribe`, which delivers a typed `GameEvent` discriminated union.

export type Unsubscribe = () => void;

// Lifecycle of the underlying transport from the UI's point of view.
// Wave H PR #17's `ConnectionBanner` renders French copy keyed off this
// union: `connecting` while the socket is opening, `connected` once
// `onopen` has fired, `disconnected` after a non-clean close, and
// `reconnecting` while a retry attempt is in flight. The adapter is
// the single source of truth — components never derive state from
// `WebSocket.readyState` directly (that would re-introduce an
// `infrastructure/` import in the `ui/` layer per ADR-0002 §7).
export type ConnectionState =
  | 'connecting'
  | 'connected'
  | 'disconnected'
  | 'reconnecting';

export interface GameClient {
  // Open the WebSocket. Resolves once the socket is OPEN. The handshake
  // payload (sessionId + pseudonym + optional code) is sent inside the
  // `joinLobby` frame, not as URL params, mirroring the AsyncAPI
  // `JoinLobby` message. ADR-0027: `code` is required for new joiners
  // and optional for already-joined sessions (reconnect bypass keyed by
  // `sessionId`); a wrong / missing code on a new join surfaces as a
  // `wrong-code` error frame the lobby route renders inline.
  connect(args: {
    lobbyId: LobbyId;
    sessionId: SessionId;
    pseudonym: Pseudonym;
    code?: string;
  }): Promise<void>;

  // Send the first frame after the socket opens; claims a slot.
  joinLobby(): void;

  // Update the sender's pseudonym.
  renameSelf(pseudonym: Pseudonym): void;

  // Owner-only. Server rejects with an `error` frame otherwise.
  setGridConfig(config: GridConfig): void;

  // Owner-only. Server rejects with an `error` frame otherwise.
  startGame(): void;

  // Player typed (or cleared) a letter. `null` clears the cell.
  cellUpdate(row: number, column: number, letter: Letter | null): void;

  // Player's focus moved to a different cell (or cleared). Carries the
  // direction (`across`/`down`) the player is solving so peers can tint
  // the whole word, not just the focused cell. `null` row/column means
  // the player has no cell focused. The adapter MUST debounce these so
  // bursts of focus changes (a fast typist auto-advancing along a word)
  // collapse to one outbound `cellFocus` frame; ADR-0018 §"Presence"
  // pins the cadence at 5 Hz / 200 ms. Solo callers never invoke this.
  cellFocus(
    row: number | null,
    column: number | null,
    direction: 'across' | 'down' | null,
  ): void;

  // Voluntary disconnect; frees the slot immediately.
  leaveLobby(): void;

  // Owner-only (ADR-0029). Mints a fresh `LobbyCode` server-side; the
  // refreshed `lobbyState` snapshot carries the new value. Non-owner
  // surfaces a `not-owner` error frame.
  rotateCode(): void;

  // Close the underlying socket without sending `leaveLobby`.
  disconnect(): void;

  // Server→client event stream. Returns an `Unsubscribe` that detaches
  // the handler. Multiple subscribers are supported.
  subscribe(handler: (event: GameEvent) => void): Unsubscribe;

  // Transport-lifecycle stream. The handler is invoked synchronously
  // with the current state on subscribe so a freshly-mounted banner
  // never flashes stale chrome. Multiple subscribers are supported.
  subscribeConnectionState(handler: (state: ConnectionState) => void): Unsubscribe;
}

// ----- Server→client event union (AsyncAPI subscribe operation) -----
// Wire-format `type` discriminator matches `serverToClient.message.oneOf`
// in game/api/asyncapi.yaml. Clients dispatch on `event.type`.

export interface LobbyStateEvent {
  readonly type: 'lobbyState';
  readonly players: readonly Player[];
  readonly ownerSessionId: SessionId;
  readonly state: LobbyLifecycleState;
  readonly gridConfig: GridConfig;
  // Always present — first-class snapshot field so future server-side
  // mutations propagate via the same channel as every other lobby field.
  readonly code: string;
  readonly game: GameSession | null;
}

export interface PlayerJoinedEvent {
  readonly type: 'playerJoined';
  readonly sessionId: SessionId;
  readonly pseudonym: Pseudonym;
  readonly joinedAt: Instant;
}

export interface PlayerLeftEvent {
  readonly type: 'playerLeft';
  readonly sessionId: SessionId;
}

export interface PlayerRenamedEvent {
  readonly type: 'playerRenamed';
  readonly sessionId: SessionId;
  readonly newPseudonym: Pseudonym;
}

export interface GameStartedEvent {
  readonly type: 'gameStarted';
  readonly puzzle: GamePuzzle;
  readonly startedAt: Instant;
}

export interface CellUpdatedEvent {
  readonly type: 'cellUpdated';
  readonly sessionId: SessionId;
  readonly row: number;
  readonly column: number;
  readonly letter: Letter | null;
  readonly writtenAt: Instant;
}

// Ephemeral cursor update — one peer's focus moved (or cleared). Carries
// no domain meaning: not persisted, not folded into game state, never
// triggers conflict resolution. Consumers (the grid's `PresenceOverlay`)
// use it to render a coloured ring + word tint + pseudonym chip on the
// peer's currently-focused cell. `null` row/column means the peer has
// nothing focused (drop their overlay). Mirrors AsyncAPI `presenceUpdated`.
export interface PresenceUpdatedEvent {
  readonly type: 'presenceUpdated';
  readonly sessionId: SessionId;
  readonly row: number | null;
  readonly column: number | null;
  readonly direction: 'across' | 'down' | null;
}

// Boolean state edge — peer started ([typing] = true) or stopped (false)
// receiving keystrokes. Server emits one frame per transition; clients
// SHOULD treat the dot as a hint, not a guarantee, since rate-limiting
// and reordering can drop the trailing `false` edge.
export interface TypingEvent {
  readonly type: 'typing';
  readonly sessionId: SessionId;
  readonly typing: boolean;
}

// Boolean state edge for the 30s inactivity threshold. Independent of
// `connectionLost` — a connected-but-idle peer is still subscribed.
export interface IdleEvent {
  readonly type: 'idle';
  readonly sessionId: SessionId;
  readonly idle: boolean;
}

// Graceful disconnect — peer's WebSocket closed but the slot is held
// during the server's grace window. Distinct from `playerLeft`: the
// roster pill should grey-out on this event and only be removed when
// (or if) `playerLeft` follows.
export interface ConnectionLostEvent {
  readonly type: 'connectionLost';
  readonly sessionId: SessionId;
}

// Server-authoritative cursor relocation when an answer that contained a
// peer's cursor was validated. Clients apply unconditionally
// (last-write-wins) and re-render the named session's cursor at the
// bumped position. Mirrors AsyncAPI `cursorBumped`.
export interface CursorBumpedEvent {
  readonly type: 'cursorBumped';
  readonly sessionId: SessionId;
  readonly row: number;
  readonly column: number;
  readonly direction: 'across' | 'down';
}

// Server broadcast: every cell in `positions` is now locked because its
// containing word was just completed correctly. Mirrors AsyncAPI
// `wordLocked`. A crossing fill that closes two words at once produces a
// single event whose `positions` is the union. Cells in `positions`
// become read-only client-side; the server silently ignores subsequent
// `cellUpdate` writes targeting any of them, so the lock is a UX hint
// over a server-enforced contract — last-write-wins still applies in
// theory, but no further `cellUpdated` rebroadcasts will follow.
export interface WordLockedEvent {
  readonly type: 'wordLocked';
  readonly positions: readonly Position[];
  readonly lockedAt: Instant;
}

export interface GameSolvedEvent {
  readonly type: 'gameSolved';
  readonly durationMs: number;
  readonly finalEntries: readonly CellEntry[];
}

// RFC 7807-shaped error frame. The wire field is `errorType` (not `type`)
// so it does not collide with the discriminator.
export interface GameErrorEvent {
  readonly type: 'error';
  readonly errorType: string;
  readonly title: string;
  readonly detail?: string;
  readonly status?: number;
}

export type GameEvent =
  | LobbyStateEvent
  | PlayerJoinedEvent
  | PlayerLeftEvent
  | PlayerRenamedEvent
  | GameStartedEvent
  | CellUpdatedEvent
  | PresenceUpdatedEvent
  | TypingEvent
  | IdleEvent
  | ConnectionLostEvent
  | CursorBumpedEvent
  | WordLockedEvent
  | GameSolvedEvent
  | GameErrorEvent;
