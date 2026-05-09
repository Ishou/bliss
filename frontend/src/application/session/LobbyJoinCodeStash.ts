import type { LobbyId } from '@/domain/game';

// Application-layer port for the per-tab lobby-code stash (ADR-0027).
// The infrastructure adapter (`SessionStorageLobbyJoinCodeStash`)
// reads/writes `sessionStorage`; routes receive it via the TanStack
// Router context so `ui/` never imports `infrastructure/` directly
// (ADR-0002 §7).
//
// Lifecycle: the `/join/$code` route and Accueil "Rejoindre" call
// `stash(lobbyId, code)` immediately before navigating; the lobby
// route's WS-open calls `consume(lobbyId)` once and gets the value
// or `null`. The adapter MUST clear the entry on consume so a
// subsequent reload finds it empty (sessionId-keyed reconnect path
// covers reload-after-join).

export interface LobbyJoinCodeStash {
  stash(lobbyId: LobbyId, code: string): void;
  /**
   * Non-destructive read. Returns the stashed code (or `null`).
   *
   * Why non-destructive: React StrictMode double-mounts route effects
   * in dev (mount → unmount → mount), and a destructive read would
   * have the first mount drain the stash before the second mount can
   * use it — sending the WS join frame without a code and getting a
   * spurious `wrong-code` rejection. SessionStorage's natural per-tab
   * lifecycle handles cleanup on tab close, and the server's reconnect
   * branch ignores the code for already-joined sessions, so a stale
   * stash on reload is harmless.
   */
  read(lobbyId: LobbyId): string | null;
  /** Explicit cleanup — call on confirmed join or on a wrong-code error. */
  clear(lobbyId: LobbyId): void;
}
