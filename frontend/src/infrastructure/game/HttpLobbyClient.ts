// HTTP adapter implementing the application-layer `LobbyClient` port.
// Wraps `createGameApiClient` and lifts RFC 7807 problem bodies into a
// typed `LobbyClientError` so the route's `errorComponent` switches on
// `error.kind` instead of HTTP status codes. Per ADR-0002 §7 only this
// layer may import the generated client; the composition root threads an
// instance through the router context (Wave G).
//
// Status → kind mapping (ADR-0003 §6):
//   400 → 'validation', 404 → 'not-found',
//   5xx → 'transient', network failure → 'upstream-unavailable'.
//
// A non-RFC 7807 response (no `type` + `title` body) is also treated
// as `upstream-unavailable` regardless of status: it means whatever
// is answering on the configured host isn't speaking the Bliss
// game-api contract — typically a wrong service occupying the port
// in local dev (the "Service indisponible" UX is what the user
// needs to see in that case, not "validation" or "not-found").

import {
  LobbyClientError,
  type LobbyClient,
  type LobbyClientErrorKind,
  type LobbySummary,
  type ProblemDetails,
} from '@/application/game';
import type { Lobby, LobbyId } from '@/domain/game';

import { createGameApiClient, type GameApiClient } from '../api/game/client';
import type { components } from '../api/game/types';

type WireLobby = components['schemas']['Lobby'];
type WireLobbySummary = components['schemas']['LobbySummary'];
type WireProblem = components['schemas']['Problem'];

export interface HttpLobbyClientOptions {
  readonly baseUrl: string;
  readonly fetch?: typeof globalThis.fetch;
}

export function createHttpLobbyClient(
  options: HttpLobbyClientOptions | { readonly client: GameApiClient },
): LobbyClient {
  const client =
    'client' in options
      ? options.client
      : createGameApiClient({ baseUrl: options.baseUrl, fetch: options.fetch });

  // Lifts a successful openapi-fetch result to the wire `Lobby`, or throws
  // a typed `LobbyClientError` for problem-details responses and network
  // rejections. Co-located with the methods so the `client` capture is
  // implicit in the call sites' closures.
  const safeRequest = async (
    call: () => Promise<{ data?: WireLobby; error?: WireProblem; response: Response }>,
  ): Promise<WireLobby> => {
    let result: Awaited<ReturnType<typeof call>>;
    try {
      result = await call();
    } catch (cause) {
      throw new LobbyClientError({
        kind: 'upstream-unavailable',
        status: null,
        problem: null,
        message: `lobby request failed: ${cause instanceof Error ? cause.message : String(cause)}`,
      });
    }
    const { data, error, response } = result;
    if (error || !data) throw liftProblem(error, response);
    return data;
  };

  // List variant of `safeRequest`. Mirrors the same kind-mapping and
  // network-error envelope but is typed for endpoints that return an
  // array body (currently `GET /v1/sessions/{sessionId}/lobbies`). An
  // empty list is a valid success — no 404 is emitted for "session has
  // no lobbies" (would leak whether the session has ever played, per
  // ADR-0039).
  const safeRequestList = async (
    call: () => Promise<{
      data?: WireLobbySummary[];
      error?: WireProblem;
      response: Response;
    }>,
  ): Promise<WireLobbySummary[]> => {
    let result: Awaited<ReturnType<typeof call>>;
    try {
      result = await call();
    } catch (cause) {
      throw new LobbyClientError({
        kind: 'upstream-unavailable',
        status: null,
        problem: null,
        message: `lobby request failed: ${cause instanceof Error ? cause.message : String(cause)}`,
      });
    }
    const { data, error, response } = result;
    if (error || !data) throw liftProblem(error, response);
    return data;
  };

  return {
    async createLobby({ ownerSessionId, ownerPseudonym }) {
      const wire = await safeRequest(() =>
        client.POST('/v1/lobbies', { body: { ownerSessionId, ownerPseudonym } }),
      );
      // Carry the server-issued `LobbyId` through to callers — the home
      // route reads it to navigate to `/lobby/:lobbyId`. `getLobby`
      // intentionally drops `id` because the route already knows it
      // from the URL (see `wireToDomain`).
      return { id: wire.id as unknown as LobbyId, ...wireToDomain(wire) };
    },
    async getLobby(lobbyId: LobbyId) {
      const wire = await safeRequest(() =>
        client.GET('/v1/lobbies/{lobbyId}', { params: { path: { lobbyId } } }),
      );
      return wireToDomain(wire);
    },
    async findByCode(code: string) {
      const wire = await safeRequest(() =>
        client.GET('/v1/lobbies/by-code/{code}', { params: { path: { code } } }),
      );
      return { id: wire.id as unknown as LobbyId, ...wireToDomain(wire) };
    },
    async listMyLobbies(sessionId) {
      const summaries = await safeRequestList(() =>
        client.GET('/v1/sessions/{sessionId}/lobbies', { params: { path: { sessionId } } }),
      );
      return summaries.map(wireToSummary);
    },
    async rebindLobbySessions(anonSessionId) {
      const { response } = await client.POST('/v1/lobbies/players/rebind', {
        credentials: 'include',
        body: { anonSessionId },
      });
      if (!response.ok) {
        throw new Error(`rebindLobbySessions failed: HTTP ${String(response.status)}`);
      }
    },
    async unbindLobbySessions(anonPseudonym) {
      const { response } = await client.POST('/v1/lobbies/players/unbind', {
        credentials: 'include',
        body: { anonPseudonym },
      });
      if (!response.ok) {
        throw new Error(`unbindLobbySessions failed: HTTP ${String(response.status)}`);
      }
    },
  };
}

function liftProblem(error: WireProblem | undefined, response: Response): LobbyClientError {
  const status = response.status;
  // A real Bliss game-api error response is RFC 7807 problem+json
  // with `type` + `title` (and `status`) populated. If those aren't
  // there, the upstream isn't speaking our contract — surface as
  // unavailable so the UI shows "Service indisponible" instead of a
  // misleading validation/not-found copy. Catches the local-dev case
  // where another process (e.g., gunicorn) occupies the port.
  if (!error || typeof error.type !== 'string' || typeof error.title !== 'string') {
    return new LobbyClientError({
      kind: 'upstream-unavailable',
      status,
      problem: null,
      message: `lobby request rejected with HTTP ${status} but no RFC 7807 problem body`,
    });
  }
  const problem: ProblemDetails = {
    type: error.type,
    title: error.title,
    status: error.status,
    ...(error.detail != null ? { detail: error.detail } : {}),
    ...(error.instance != null ? { instance: error.instance } : {}),
  };
  const message = problem.detail ?? problem.title;
  return new LobbyClientError({ kind: statusToKind(status), status, problem, message });
}

function statusToKind(status: number): LobbyClientErrorKind {
  if (status === 404) return 'not-found';
  if (status >= 500) return 'transient';
  return 'validation';
}

// Wire→domain `Lobby` mapping. The wire shape is structurally identical
// except for the wire-only `id` field (the route already knows it from
// the URL — see `domain/game/types.ts`). The branded ID strings are
// runtime `string`s, so the `unknown` cast localizes the brand assertion
// at this single seam.
function wireToDomain(wire: WireLobby): Lobby {
  return {
    players: wire.players as unknown as Lobby['players'],
    ownerSessionId: wire.ownerSessionId as unknown as Lobby['ownerSessionId'],
    state: wire.state,
    gridConfig: wire.gridConfig,
    game: wire.game as unknown as Lobby['game'],
    code: wire.code ?? null,
  };
}

// Wire→domain `LobbySummary` mapping. Same structural shape — the cast
// localizes the brand assertion on `id` at this seam. `title` is
// optional on the wire (absent for untitled lobbies, never `null` per
// ADR-0003 §6); we forward it as-is so callers can distinguish
// "untitled" from "blank title".
function wireToSummary(wire: WireLobbySummary): LobbySummary {
  return {
    id: wire.id as unknown as LobbyId,
    code: wire.code,
    state: wire.state,
    gridConfig: { width: wire.gridConfig.width, height: wire.gridConfig.height },
    playerCount: wire.playerCount,
    connectedCount: wire.connectedCount,
    lastActivityAt: wire.lastActivityAt,
    progress: {
      solvedCells: wire.progress.solvedCells,
      totalCells: wire.progress.totalCells,
    },
    ...(wire.title != null ? { title: wire.title } : {}),
  };
}
