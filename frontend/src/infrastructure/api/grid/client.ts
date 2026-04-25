// Typed Grid API client.
//
// Per ADR-0003 §3 the runtime is `openapi-fetch` and the types are generated
// by `openapi-typescript` into `./types.ts` from `grid/api/openapi.yaml`. The
// CI regen-and-diff gate (per ADR-0003 §8.2) guarantees the committed types
// match the spec; this file is only the thin factory that ties the runtime to
// the types.
//
// This module lives in the infrastructure layer per ADR-0002 §7: only
// `src/infrastructure/**` may import generated API code, and only the
// application layer may import this client. UI components never call HTTP
// directly.
//
// The first endpoint (`GET /v1/puzzles/{puzzleId}`) lands in the consumer
// workstream; this PR ships the contract-side wrapper only.
import createClient, { type Client, type ClientOptions } from 'openapi-fetch';

import type { paths } from './types';

/**
 * Options accepted by `createGridApiClient`. A subset of `openapi-fetch`'s
 * `ClientOptions` — narrowed because the API only needs a base URL and an
 * optional fetch override (the latter useful for tests and Discord-Activity
 * embedding where `globalThis.fetch` may be sandboxed).
 */
export interface GridApiClientOptions {
  /**
   * Absolute base URL of the Grid API, including any version-agnostic prefix
   * the deployment exposes (e.g. `https://api.bliss.example/grid`). Operation
   * paths from the spec (`/v1/puzzles/{puzzleId}`) are appended verbatim.
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
 * Build a typed Grid API client. The returned client is fully typed against
 * the OpenAPI spec — operations, parameters, and responses are checked at
 * compile time. No request is issued by this factory; callers invoke
 * `client.GET('/v1/puzzles/{puzzleId}', { params: { path: { puzzleId } } })`
 * when they need data.
 */
export function createGridApiClient(options: GridApiClientOptions): Client<paths> {
  return createClient<paths>({
    baseUrl: options.baseUrl,
    fetch: options.fetch,
  });
}

export type GridApiClient = Client<paths>;
export type { paths } from './types';
