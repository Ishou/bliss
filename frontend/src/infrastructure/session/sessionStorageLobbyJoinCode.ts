// Infrastructure adapter for `LobbyJoinCodeStash` — sessionStorage-backed.
// ADR-0027: the stash is per-tab, one-shot, and cleared on consume so
// reload-after-join falls through to the sessionId-keyed reconnect
// path on the server.
//
// Server-side / SSR-safe: every method bails when `sessionStorage` is
// undefined (the only environment the wider codebase runs in is the
// browser, but vitest's jsdom does occasionally end up with it
// undefined under StrictMode unmount-remount, and the helper is cheap
// enough to guard).

import type { LobbyJoinCodeStash } from '@/application/session/LobbyJoinCodeStash';
import type { LobbyId } from '@/domain/game';

const STASH_PREFIX = 'bliss.lobbyJoinCode.';

const keyFor = (lobbyId: LobbyId): string => `${STASH_PREFIX}${lobbyId}`;

export const sessionStorageLobbyJoinCodeStash: LobbyJoinCodeStash = {
  stash(lobbyId, code) {
    if (typeof sessionStorage === 'undefined') return;
    sessionStorage.setItem(keyFor(lobbyId), code);
  },
  read(lobbyId) {
    if (typeof sessionStorage === 'undefined') return null;
    return sessionStorage.getItem(keyFor(lobbyId));
  },
  clear(lobbyId) {
    if (typeof sessionStorage === 'undefined') return;
    sessionStorage.removeItem(keyFor(lobbyId));
  },
};
