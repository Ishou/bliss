// HTTP adapter for the session-erasure RGPD endpoint.
//
// Fans the "Effacer mes données" click out to BOTH backends:
//   - grid-api  DELETE /v1/sessions/{sessionId} → removes puzzle_hint_usage rows.
//   - game-api  DELETE /v1/sessions/{sessionId} → ADR-0039 three-rule cascade
//                                                 over multiplayer lobby state.
//
// Both must succeed for the UI to claim data was erased; a partial failure
// throws so the caller surfaces "Veuillez réessayer" instead of a green
// confirmation that would itself be a privacy regression.
//
// Returns the network-transport slice of SessionClient (`eraseSession`).
// The composition root (`main.tsx`) merges this with the localStorage helpers
// to produce a full SessionClient — see ADR-0025 §5.

import type { SessionClient } from '@/application/session/SessionClient';
import { createGameApiClient } from '../game/client';
import { createGridApiClient } from './client';

export interface CreateHttpSessionClientOptions {
  /** Base URL of the grid-api (puzzle + hint endpoints). */
  readonly gridBaseUrl: string;
  /**
   * Base URL of the game-api. When undefined the multiplayer fan-out is
   * skipped — useful for environments where the multiplayer flag is off and
   * game-api isn't reachable. The grid-api call still runs.
   */
  readonly gameBaseUrl?: string;
  readonly fetch?: typeof fetch;
}

export function createHttpSessionClient(
  options: CreateHttpSessionClientOptions,
): Pick<SessionClient, 'eraseSession'> {
  const gridClient = createGridApiClient({
    baseUrl: options.gridBaseUrl,
    fetch: options.fetch,
  });
  const gameClient = options.gameBaseUrl
    ? createGameApiClient({ baseUrl: options.gameBaseUrl, fetch: options.fetch })
    : null;

  return {
    async eraseSession(sessionId) {
      // Run both calls in parallel; failure on either propagates as a hard error.
      // `Promise.all` short-circuits on first rejection, so the UI never claims
      // success when one side did not actually erase.
      const gridPromise = gridClient.DELETE('/v1/sessions/{sessionId}', {
        params: { path: { sessionId } },
      });
      const gamePromise = gameClient
        ? gameClient.DELETE('/v1/sessions/{sessionId}', {
            params: { path: { sessionId } },
          })
        : Promise.resolve(null);
      const [gridResult, gameResult] = await Promise.all([gridPromise, gamePromise]);

      if (gridResult.error) {
        throw new Error(
          `eraseSession (grid) failed: ${String(gridResult.response.status)} ${JSON.stringify(gridResult.error)}`,
        );
      }
      if (gameResult && gameResult.error) {
        throw new Error(
          `eraseSession (game) failed: ${String(gameResult.response.status)} ${JSON.stringify(gameResult.error)}`,
        );
      }
      return { deleted: gridResult.data?.deleted ?? 0 };
    },
  };
}
