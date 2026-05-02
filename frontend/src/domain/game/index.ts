// Public surface of the game/ frontend domain. Pure types only —
// no runtime, no framework imports — per ADR-0002 §7.
export type {
  CellEntry,
  GameArrowDirection,
  GameBlockCell,
  GameCell,
  GameClueDirection,
  GameDefinitionCell,
  GameDefinitionClue,
  GameLetterCell,
  GamePuzzle,
  GameSession,
  GridConfig,
  Instant,
  Letter,
  Lobby,
  LobbyId,
  LobbyLifecycleState,
  Player,
  Position,
  Pseudonym,
  SessionId,
} from './types';
