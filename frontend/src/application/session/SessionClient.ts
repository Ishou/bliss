// Application-layer port for session erasure. Pure interface — concrete
// adapters (HTTP) live in `@/infrastructure`. Defined here so `ui/`
// routes can depend on the contract without importing the adapter, per
// the boundaries:element-types rule (ADR-0002 §7).

export interface SessionClient {
  /**
   * Erase server-side data for [sessionId]. Always resolves: a session id
   * with no prior data returns `{ deleted: 0 }`. Throws on network error
   * or non-2xx.
   */
  eraseSession(sessionId: string): Promise<{ deleted: number }>;
}
