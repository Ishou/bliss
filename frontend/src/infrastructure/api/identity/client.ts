// Typed Identity API client (ADR-0003 §3). Generated types in `./types.ts`.
// Factory does NOT force `credentials: 'include'`; callers opt in per call.
import createClient, { type Client, type ClientOptions } from 'openapi-fetch';
import { uuidv7 } from 'uuidv7';

import type { paths } from './types';

export interface IdentityApiClientOptions {
  readonly baseUrl: string;
  readonly fetch?: ClientOptions['fetch'];
}

export function createIdentityApiClient(
  options: IdentityApiClientOptions,
): Client<paths> {
  const client = createClient<paths>({
    baseUrl: options.baseUrl,
    fetch: options.fetch,
  });
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

export type IdentityApiClient = Client<paths>;
export type { paths } from './types';
