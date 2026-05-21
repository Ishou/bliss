// Typed Game API client.
//
// Per ADR-0003 §3 the runtime is `openapi-fetch` and the types are generated
// by `openapi-typescript` into `./types.ts` from `game/api/openapi.yaml`. The
// CI regen-and-diff gate (per ADR-0003 §8.2) guarantees the committed types
// match the spec; this file is only the thin factory that ties the runtime to
// the types.
//
// This module lives in the infrastructure layer per ADR-0002 §7: only
// `src/infrastructure/**` may import generated API code, and only the
// application layer may import this client. UI components never call HTTP
// directly.
//
// Mirrors `infrastructure/api/grid/client.ts`. Two clients exist because the
// grid and game bounded contexts have separate OpenAPI specs (ADR-0001 §1).
import createClient, { type Client, type ClientOptions } from 'openapi-fetch';
import { uuidv7 } from 'uuidv7';

import type { paths } from './types';

/**
 * Options accepted by `createGameApiClient`. A subset of `openapi-fetch`'s
 * `ClientOptions` — narrowed because the API only needs a base URL and an
 * optional fetch override (the latter useful for tests and Discord-Activity
 * embedding where `globalThis.fetch` may be sandboxed).
 */
export interface GameApiClientOptions {
  /**
   * Absolute base URL of the Game API, including any version-agnostic prefix
   * the deployment exposes (e.g. `https://game.wordsparrow.io`). Operation
   * paths from the spec (`/v1/lobbies`, `/v1/lobbies/{lobbyId}`) are
   * appended verbatim.
   */
  readonly baseUrl: string;
  /**
   * Optional `fetch` implementation. Defaults to the global `fetch`. Provided
   * for tests and constrained runtimes; not for vendor SDKs (which are
   * forbidden in this layer per ADR-0003 §1, ADR-0002 §7).
   */
  readonly fetch?: ClientOptions['fetch'];
}

/**
 * Build a typed Game API client. The returned client is fully typed against
 * the OpenAPI spec — operations, parameters, and responses are checked at
 * compile time. No request is issued by this factory; callers invoke
 * `client.POST('/v1/lobbies', { body: ... })` or
 * `client.GET('/v1/lobbies/{lobbyId}', { params: { path: { lobbyId } } })`
 * when they need data.
 */
export function createGameApiClient(options: GameApiClientOptions): Client<paths> {
  const client = createClient<paths>({
    baseUrl: options.baseUrl,
    fetch: options.fetch,
    // Send the __Secure-ws_session cookie cross-subdomain so authed callers
    // can be identified by game-api (lobby create / read uses the cookie's
    // displayName when present). Same-origin policy with the wordsparrow.io
    // parent domain makes this safe.
    credentials: 'include',
  });
  // WebSocket connections do not support custom request headers from the browser
  // -- instrumenting them is a follow-up (likely via the connect URL).
  client.use({
    onRequest({ request }) {
      if (!request.headers.has('X-Request-Id')) {
        request.headers.set('X-Request-Id', uuidv7());
      }
      return request;
    },
  });
  return client;
}

export type GameApiClient = Client<paths>;
export type { paths } from './types';
