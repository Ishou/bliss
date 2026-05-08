// HTTP adapter for the grid-api session lifecycle endpoints.
//
// Currently exposes only `eraseSession` — the right-to-erasure call
// (RGPD Article 17, ADR-0025 §5). The matching backend route is
// `DELETE /v1/sessions/{sessionId}` on grid-api.

import type { SessionClient } from '@/application/session/SessionClient';
import { createGridApiClient } from './client';

export interface CreateHttpSessionClientOptions {
  readonly baseUrl: string;
  readonly fetch?: typeof fetch;
}

export function createHttpSessionClient(options: CreateHttpSessionClientOptions): SessionClient {
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
