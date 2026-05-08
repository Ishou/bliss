// Application-layer port for session management. Pure interface — concrete
// adapters live in `@/infrastructure`. Defined here so `ui/` routes can
// depend on the contract without importing the adapter, per the
// boundaries:element-types rule (ADR-0002 §7).
//
// The composition root (`main.tsx`) wires the HTTP adapter (`eraseSession`)
// together with the localStorage helpers (`getSessionId`, `clearLocalSession`)
// into a single concrete implementation of this interface.

export interface SessionClient {
  /**
   * Erase server-side data for [sessionId]. Always resolves: a session id
   * with no prior data returns `{ deleted: 0 }`. Throws on network error
   * or non-2xx.
   */
  eraseSession(sessionId: string): Promise<{ deleted: number }>;
  /** Return the current session id from localStorage, creating one if absent. */
  getSessionId(): string;
  /** Wipe the session id and pseudonym from localStorage. */
  clearLocalSession(): void;
}
