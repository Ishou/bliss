// MSW handlers for the game/ bounded context — lobby REST endpoints.
// Mounted only in preview builds (`VITE_USE_MOCK_API=true`); the entire
// `mocks/` tree tree-shakes out of production. Per ADR-0007 §5 these
// handlers replay the spec contract so the route loader's GET resolves
// the lobby the create-button just minted.
//
// Wire shapes mirror `game/api/openapi.yaml` verbatim. Identifier formats
// per ADR-0003 §6 and ADR-0020 (LobbyId is base58 nanoid, sessions are
// UUID v7); errors are RFC 7807.
//
// The WebSocket handler (`lobbyWs.addEventListener`) ships in the follow-up
// PR once this REST foundation is merged; `VITE_FEATURE_MULTIPLAYER` stays
// off in `.env.preview` until that point.

import { http, HttpResponse } from 'msw';

import type { components } from '@/infrastructure/api/game/types';

import {
  generateLobbyId,
  getLobby,
  putLobby,
  type Lobby,
  type Player,
} from './lobbyStore';

type CreateLobbyRequest = components['schemas']['CreateLobbyRequest'];
type Problem = components['schemas']['Problem'];

// LobbyId per ADR-0020: 8 chars from the URL-safe base58 alphabet.
const LOBBY_ID_PATTERN = /^[1-9A-HJ-NP-Za-km-z]{8}$/;

const nowIso = (): string => new Date().toISOString();

function problem(status: number, type: string, title: string, detail: string): Response {
  const body: Problem = { type, title, status, detail };
  return HttpResponse.json(body, {
    status,
    headers: { 'content-type': 'application/problem+json' },
  });
}

export const gameHandlers = [
  // POST /v1/lobbies — owner creates a lobby; server mints LobbyId and
  // returns the full resource. Persists in the in-memory store so the
  // subsequent route loader's `GET /v1/lobbies/:id` resolves the body.
  http.post('*/v1/lobbies', async ({ request }) => {
    let body: CreateLobbyRequest;
    try {
      body = (await request.json()) as CreateLobbyRequest;
    } catch {
      return problem(
        400,
        'https://bliss.example/errors/invalid-lobby-create-request',
        'Invalid request body',
        'Body is not valid JSON.',
      );
    }
    if (!body?.ownerSessionId || !body?.ownerPseudonym) {
      return problem(
        400,
        'https://bliss.example/errors/invalid-lobby-create-request',
        'Invalid request body',
        '`ownerSessionId` and `ownerPseudonym` are required.',
      );
    }

    const id = generateLobbyId();
    const player: Player = {
      sessionId: body.ownerSessionId,
      pseudonym: body.ownerPseudonym,
      joinedAt: nowIso(),
    };
    const lobby: Lobby = {
      id,
      ownerSessionId: body.ownerSessionId,
      players: [player],
      state: 'WAITING',
      gridConfig: { width: 5, height: 5 },
      // ADR-0003 §6: `game` is in `required`; `null` (still WAITING) is
      // the explicit blank value, not absence.
      game: null,
    };
    putLobby(lobby);

    return HttpResponse.json(lobby, {
      status: 201,
      headers: { Location: `/v1/lobbies/${id}` },
    });
  }),

  // GET /v1/lobbies/:lobbyId — replay the persisted lobby.
  http.get('*/v1/lobbies/:lobbyId', ({ params }) => {
    const lobbyId = String(params.lobbyId);
    if (!LOBBY_ID_PATTERN.test(lobbyId)) {
      return problem(
        400,
        'https://bliss.example/errors/invalid-lobby-id',
        'Invalid lobbyId',
        `\`${lobbyId}\` does not match the base58 nanoid pattern.`,
      );
    }
    const lobby = getLobby(lobbyId);
    if (!lobby) {
      return problem(
        404,
        'https://bliss.example/errors/lobby-not-found',
        'Lobby not found',
        `No lobby with id ${lobbyId}.`,
      );
    }
    return HttpResponse.json(lobby);
  }),
];
