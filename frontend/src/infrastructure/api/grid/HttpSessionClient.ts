// HTTP adapter for the grid-api session lifecycle endpoints.
//
// Returns only the network-transport slice of SessionClient (`eraseSession`).
// The composition root (`main.tsx`) merges this with the localStorage helpers
// to produce a full SessionClient — see ADR-0025 §5.

import type { SessionClient } from '@/application/session/SessionClient';
import { createGridApiClient } from './client';

export interface CreateHttpSessionClientOptions {
  readonly baseUrl: string;
  readonly fetch?: typeof fetch;
}

export function createHttpSessionClient(
  options: CreateHttpSessionClientOptions,
): Pick<SessionClient, 'eraseSession'> {
  const client = createGridApiClient({ baseUrl: options.baseUrl, fetch: options.fetch });
  return {
    async eraseSession(sessionId) {
      const result = await client.DELETE('/v1/sessions/{sessionId}', {
        params: { path: { sessionId } },
      });
      if (result.error) {
        throw new Error(
          `eraseSession failed: ${String(result.response.status)} ${JSON.stringify(result.error)}`,
        );
      }
      return { deleted: result.data?.deleted ?? 0 };
    },
  };
}
