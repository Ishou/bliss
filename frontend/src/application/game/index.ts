// Public surface of the game/ frontend application layer. The
// `WebSocketGameClient` adapter that implements `GameClient` and the
// `HttpLobbyClient` adapter that implements `LobbyClient` both live in
// `infrastructure/` (Wave E PR #134, follow-up PR for HttpLobbyClient).
export type {
  CellUpdatedEvent,
  GameClient,
  GameErrorEvent,
  GameEvent,
  GameSolvedEvent,
  GameStartedEvent,
  LobbyStateEvent,
  PlayerJoinedEvent,
  PlayerLeftEvent,
  PlayerRenamedEvent,
  Unsubscribe,
} from './GameClient';
export {
  LobbyClientError,
  type LobbyClient,
  type LobbyClientErrorKind,
  type ProblemDetails,
} from './LobbyClient';
