import type {
  CellEntry,
  GamePuzzle,
  GridConfig,
  Instant,
  Letter,
  LobbyId,
  LobbyLifecycleState,
  Player,
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

export interface GameClient {
  // Open the WebSocket. Resolves once the socket is OPEN. The handshake
  // payload (sessionId + pseudonym) is sent inside the `joinLobby` frame,
  // not as URL params, mirroring the AsyncAPI `JoinLobby` message.
  connect(args: {
    lobbyId: LobbyId;
    sessionId: SessionId;
    pseudonym: Pseudonym;
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

  // Voluntary disconnect; frees the slot immediately.
  leaveLobby(): void;

  // Close the underlying socket without sending `leaveLobby`.
  disconnect(): void;

  // Server→client event stream. Returns an `Unsubscribe` that detaches
  // the handler. Multiple subscribers are supported.
  subscribe(handler: (event: GameEvent) => void): Unsubscribe;
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
  | GameSolvedEvent
  | GameErrorEvent;
