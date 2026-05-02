// Public surface of the game/ frontend application layer. The
// `WebSocketGameClient` adapter that implements `GameClient` lives in
// `infrastructure/` (Wave E PR #13).
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
