// Public surface of the game/ frontend domain. Pure types only —
// no runtime, no framework imports — per ADR-0002 §7. The single value
// export (`MAX_PSEUDONYM_LENGTH`) is a domain constant mirroring the
// Kotlin `Pseudonym.MAX_LENGTH`; safe under §7 because it carries no
// runtime behaviour.
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
export { MAX_PSEUDONYM_LENGTH } from './types';
