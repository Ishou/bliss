// MSW handlers for the game/ bounded context (lobby REST + lobby WS).
// Mounted whenever `VITE_MOCK_GAME_API=true` — Cloudflare Pages
// previews opt in so a reviewer can click "Créer une partie", land on
// `/lobby/:id`, see the WaitingRoom, start a game, type into the
// grid, and feel another player typing back without a backend running.
// `pnpm dev` keeps this off by default so contributors run against a
// real game-api on localhost; a `.env.development.local` flip is the
// fallback when a contributor doesn't have it running. The entire
// `mocks/` tree tree-shakes out of production builds (both flags off
// in `.env`).
//
// Wire shapes mirror `game/api/openapi.yaml` (REST) and
// `game/api/asyncapi.yaml` (WebSocket) verbatim. Identifier formats per
// ADR-0003 §6 and ADR-0020 (LobbyId is base58 nanoid, sessions are
// UUID v7); errors are RFC 7807.

import { http, HttpResponse, ws } from 'msw';

import type { components } from '@/infrastructure/api/game/types';

import {
  BOT_PSEUDONYM,
  BOT_SESSION_ID,
  MOCK_ANSWERS,
  buildGameSession,
  generateLobbyCode,
  generateLobbyId,
  getLobby,
  getLobbyByCode,
  putLobby,
  updateLobby,
  type Lobby,
  type Player,
} from './lobbyStore';

type CreateLobbyRequest = components['schemas']['CreateLobbyRequest'];
type Problem = components['schemas']['Problem'];
type WireLobbySummary = components['schemas']['LobbySummary'];

// LobbyId per ADR-0020: 8 chars from the URL-safe base58 alphabet.
const LOBBY_ID_PATTERN = /^[1-9A-HJ-NP-Za-km-z]{8}$/;

// `*://*/...` glob so MSW intercepts whatever host the adapters use —
// `wss://game.wordsparrow.io` in preview, `ws://localhost:7778` in dev.
const lobbyWs = ws.link('*://*/v1/lobbies/:lobbyId/ws');

// Bot cadence — random in 4–8 s so the demo feels alive without being
// frantic. 3-s delay before join lets the reviewer see the empty
// WaitingRoom first, then watch the bot pop in.
const BOT_TICK_MIN_MS = 4_000;
const BOT_TICK_MAX_MS = 8_000;
const BOT_JOIN_DELAY_MS = 3_000;
// Bot presence cadence — slightly faster than the typing tick so the
// reviewer sees the cursor wander between keystrokes. ~3 s feels alive
// without being distracting on a single screen.
const BOT_PRESENCE_TICK_MS = 3_000;

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
const randomLetter = (): string =>
  ALPHABET.charAt(Math.floor(Math.random() * ALPHABET.length));
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
      // Mirror the server: every new lobby carries a Crockford-style
      // join code that the Accueil "Rejoindre avec un code" flow types.
      code: generateLobbyCode(),
    };
    putLobby(lobby);

    return HttpResponse.json(lobby, {
      status: 201,
      headers: { Location: `/v1/lobbies/${id}` },
    });
  }),

  // GET /v1/lobbies/by-code/:code — must match before the `:lobbyId`
  // catch-all below; MSW dispatches the first matching handler.
  http.get('*/v1/lobbies/by-code/:code', ({ params }) => {
    const code = String(params.code);
    if (!/^[A-HJKM-NP-Z2-9]{6}$/.test(code)) {
      return problem(
        400,
        'https://bliss.example/errors/invalid-lobby-code',
        'Invalid code',
        `\`${code}\` does not match the lobby-code pattern.`,
      );
    }
    const lobby = getLobbyByCode(code);
    if (!lobby) {
      return problem(
        404,
        'https://bliss.example/errors/lobby-not-found',
        'Lobby not found',
        `No lobby with code ${code}.`,
      );
    }
    return HttpResponse.json(lobby);
  }),

  // GET /v1/sessions/:sessionId/lobbies — list lobbies the session is
  // a member of. ADR-0039 "Mes parties" surface. Preview returns an
  // empty list by default; PR #9's tests use `lobbySummaryFixture`
  // through `server.use(...)` to override per-test. No 404 here — an
  // empty array is the "no lobbies" answer (would leak whether the
  // session has ever played otherwise).
  http.get('*/v1/sessions/:sessionId/lobbies', () => HttpResponse.json([])),

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

// `addEventListener` returns the `WebSocketHandler` that `setupWorker`
// expects in its handler array — capture and export it below.
const lobbyWsHandler = lobbyWs.addEventListener('connection', ({ client, params }) => {
  const lobbyId = String(params.lobbyId);
  // Per-connection state. Closing the socket clears every timer so a
  // reload does not leak setTimeout callbacks into the next session.
  let joinTimer: ReturnType<typeof setTimeout> | null = null;
  let tickTimer: ReturnType<typeof setTimeout> | null = null;
  let presenceTimer: ReturnType<typeof setTimeout> | null = null;
  let botJoined = false;
  // CellKey → latest letter, used for the relaxed "all cells filled"
  // gameSolved trigger.
  const cellEntries = new Map<string, string>();
  const cellKey = (row: number, column: number): string => `${row},${column}`;

  const sendSnapshot = (): void => {
    const lobby = getLobby(lobbyId);
    if (!lobby) return;
    client.send(
      JSON.stringify({
        type: 'lobbyState',
        players: lobby.players,
        ownerSessionId: lobby.ownerSessionId,
        state: lobby.state,
        gridConfig: lobby.gridConfig,
        // First-class snapshot field — mirrors the wire schema.
        code: lobby.code,
        game: lobby.game,
      }),
    );
  };

  const ensureBotJoined = (): void => {
    if (botJoined) return;
    const joinedAt = nowIso();
    updateLobby(lobbyId, (current) =>
      current.players.some((p) => p.sessionId === BOT_SESSION_ID)
        ? current
        : {
            ...current,
            players: [
              ...current.players,
              { sessionId: BOT_SESSION_ID, pseudonym: BOT_PSEUDONYM, joinedAt },
            ],
          },
    );
    botJoined = true;
    client.send(
      JSON.stringify({
        type: 'playerJoined',
        sessionId: BOT_SESSION_ID,
        pseudonym: BOT_PSEUDONYM,
        joinedAt,
      }),
    );
  };

  // Bot keystroke loop. Picks a random letter cell and emits a
  // `cellUpdated` frame stamped as the bot. Reschedules itself so the
  // cadence randomizes per beat. Halts when the lobby leaves IN_PROGRESS.
  const scheduleBotTick = (): void => {
    const delay =
      BOT_TICK_MIN_MS + Math.floor(Math.random() * (BOT_TICK_MAX_MS - BOT_TICK_MIN_MS));
    tickTimer = setTimeout(() => {
      const lobby = getLobby(lobbyId);
      if (!lobby || lobby.state !== 'IN_PROGRESS' || !lobby.game) return;
      const letterCells = lobby.game.puzzle.cells.filter((c) => c.kind === 'letter');
      if (letterCells.length === 0) return;
      const target = letterCells[Math.floor(Math.random() * letterCells.length)]!;
      client.send(
        JSON.stringify({
          type: 'cellUpdated',
          sessionId: BOT_SESSION_ID,
          row: target.position.row,
          column: target.position.column,
          letter: randomLetter(),
          writtenAt: nowIso(),
        }),
      );
      scheduleBotTick();
    }, delay);
  };

  // Bot presence loop. Emits a `presenceUpdated` frame at a random
  // letter cell every ~3 s while IN_PROGRESS, so reviewers can see the
  // PresenceOverlay (cursor ring + word tint + chip) in action without
  // opening a second tab. Direction alternates randomly so the word
  // tint changes axis between ticks. Halts when the lobby leaves
  // IN_PROGRESS — the closing-tick guard inside ensures we drop the
  // timer once the game is solved.
  const scheduleBotPresence = (): void => {
    presenceTimer = setTimeout(() => {
      const lobby = getLobby(lobbyId);
      if (!lobby || lobby.state !== 'IN_PROGRESS' || !lobby.game) return;
      const letterCells = lobby.game.puzzle.cells.filter((c) => c.kind === 'letter');
      if (letterCells.length === 0) return;
      const target = letterCells[Math.floor(Math.random() * letterCells.length)]!;
      const direction = Math.random() < 0.5 ? 'across' : 'down';
      client.send(
        JSON.stringify({
          type: 'presenceUpdated',
          sessionId: BOT_SESSION_ID,
          row: target.position.row,
          column: target.position.column,
          direction,
        }),
      );
      scheduleBotPresence();
    }, BOT_PRESENCE_TICK_MS);
  };

  // Track positions already locked so we never re-emit a `wordLocked` for
  // the same word. Mirrors the server-side monotonic-additive contract.
  const lockedKeys = new Set<string>();

  // After a successful `cellUpdate`, walk every clue containing the
  // just-filled position; for each whose letters all match `MOCK_ANSWERS`,
  // emit a `wordLocked`. Crossing fills emit one frame whose `positions`
  // is the union of both words.
  const maybeFireWordLocked = (lobby: Lobby, justRow: number, justCol: number): void => {
    if (!lobby.game) return;
    const clues = lobby.game.puzzle.clues;
    const newLocks: Array<{ row: number; column: number }> = [];
    for (const clue of clues) {
      const positions: Array<{ row: number; column: number }> = [];
      for (let i = 0; i < clue.length; i++) {
        const r = clue.start.row + (clue.direction === 'down' ? i : 0);
        const c = clue.start.column + (clue.direction === 'across' ? i : 0);
        positions.push({ row: r, column: c });
      }
      const includesJust = positions.some(
        (p) => p.row === justRow && p.column === justCol,
      );
      if (!includesJust) continue;
      // Skip already-locked words.
      if (positions.every((p) => lockedKeys.has(`${p.row},${p.column}`))) continue;
      const allCorrect = positions.every((p) => {
        const key = `${p.row},${p.column}`;
        const placed = cellEntries.get(key);
        const expected = MOCK_ANSWERS.get(key);
        return placed != null && expected != null && placed === expected;
      });
      if (!allCorrect) continue;
      for (const p of positions) {
        const key = `${p.row},${p.column}`;
        if (!lockedKeys.has(key)) {
          lockedKeys.add(key);
          newLocks.push(p);
        }
      }
    }
    if (newLocks.length === 0) return;
    updateLobby(lobbyId, (current) => ({
      ...current,
      game: current.game
        ? {
            ...current.game,
            lockedPositions: [
              ...current.game.lockedPositions,
              ...newLocks,
            ].sort((a, b) => a.row - b.row || a.column - b.column),
          }
        : current.game,
    }));
    client.send(
      JSON.stringify({
        type: 'wordLocked',
        positions: newLocks,
        lockedAt: nowIso(),
      }),
    );
  };

  // Mock relaxation: emit `gameSolved` once every non-block cell holds
  // any letter. The route consumes `durationMs` to freeze the timer
  // and the modal; `finalEntries` is the cell list we just collected.
  const maybeFireGameSolved = (): void => {
    const lobby = getLobby(lobbyId);
    if (!lobby || lobby.state !== 'IN_PROGRESS' || !lobby.game) return;
    const letterCells = lobby.game.puzzle.cells.filter((c) => c.kind === 'letter');
    if (letterCells.length === 0 || cellEntries.size < letterCells.length) return;
    const durationMs = Date.now() - new Date(lobby.game.startedAt).getTime();
    updateLobby(lobbyId, (current) => ({
      ...current,
      state: 'COMPLETED',
      game: current.game ? { ...current.game, completedAt: nowIso() } : current.game,
    }));
    if (tickTimer) clearTimeout(tickTimer);
    tickTimer = null;
    if (presenceTimer) clearTimeout(presenceTimer);
    presenceTimer = null;
    client.send(
      JSON.stringify({
        type: 'gameSolved',
        durationMs,
        finalEntries: Array.from(cellEntries.entries()).map(([key, letter]) => {
          const [row, column] = key.split(',').map(Number);
          return { row: row!, column: column!, letter };
        }),
      }),
    );
  };

  // 1. Initial snapshot (or close if the lobby vanished across a hot
  //    reload — same as the real server's behavior).
  if (!getLobby(lobbyId)) {
    client.close(1011, 'lobby not found');
    return;
  }
  sendSnapshot();

  // 2. Schedule the bot's delayed join.
  joinTimer = setTimeout(() => {
    ensureBotJoined();
    sendSnapshot();
  }, BOT_JOIN_DELAY_MS);

  // 3. Inbound message handler. Top-level `type` discriminates per
  //    AsyncAPI; unknown frames are silently dropped (forward-compat).
  client.addEventListener('message', (event) => {
    const raw = typeof event.data === 'string' ? event.data : null;
    if (!raw) return;
    let parsed: unknown;
    try {
      parsed = JSON.parse(raw);
    } catch {
      return;
    }
    if (!parsed || typeof parsed !== 'object' || !('type' in parsed)) return;
    const type = (parsed as { type: unknown }).type;

    switch (type) {
      case 'joinLobby': {
        const join = parsed as unknown as {
          sessionId: string;
          pseudonym: string;
          code?: string;
        };
        // ADR-0027: code-gate for new joiners; reconnects (sessionId
        // already a member) bypass the check by construction.
        const lobbyNow = getLobby(lobbyId);
        const isReconnect = lobbyNow?.players.some((p) => p.sessionId === join.sessionId) ?? false;
        if (!isReconnect && (join.code == null || join.code !== lobbyNow?.code)) {
          client.send(
            JSON.stringify({
              type: 'error',
              errorType: 'https://bliss.example/errors/wrong-code',
              title: 'Code de partie invalide',
              detail: 'Demandez le code à l’organisateur.',
              status: 403,
            }),
          );
          return;
        }
        const joinedAt = nowIso();
        updateLobby(lobbyId, (current) =>
          current.players.some((p) => p.sessionId === join.sessionId)
            ? current
            : {
                ...current,
                players: [
                  ...current.players,
                  { sessionId: join.sessionId, pseudonym: join.pseudonym, joinedAt },
                ],
              },
        );
        client.send(
          JSON.stringify({
            type: 'playerJoined',
            sessionId: join.sessionId,
            pseudonym: join.pseudonym,
            joinedAt,
          }),
        );
        return;
      }

      case 'setGridConfig': {
        const cfg = parsed as unknown as { width: number; height: number };
        updateLobby(lobbyId, (current) => ({
          ...current,
          gridConfig: { width: cfg.width, height: cfg.height },
        }));
        sendSnapshot();
        return;
      }

      case 'startGame': {
        const session = buildGameSession();
        updateLobby(lobbyId, (current) => ({
          ...current,
          state: 'IN_PROGRESS',
          game: session,
        }));
        client.send(
          JSON.stringify({
            type: 'gameStarted',
            puzzle: session.puzzle,
            startedAt: session.startedAt,
          }),
        );
        scheduleBotTick();
        scheduleBotPresence();
        return;
      }

      case 'cellUpdate': {
        const upd = parsed as unknown as {
          row: number;
          column: number;
          letter: string | null;
        };
        const key = cellKey(upd.row, upd.column);
        // Locked cells reject writes silently — no broadcast, no state
        // mutation. Mirrors the server's `UpdateCellUseCase` short-circuit
        // when `position in session.lockedPositions`.
        if (lockedKeys.has(key)) return;
        const lobby = getLobby(lobbyId);
        const ownerSessionId =
          lobby?.ownerSessionId ?? '00000000-0000-0000-0000-000000000000';
        // Echo via canonical broadcast — last-write-wins, server-stamped.
        client.send(
          JSON.stringify({
            type: 'cellUpdated',
            sessionId: ownerSessionId,
            row: upd.row,
            column: upd.column,
            letter: upd.letter,
            writtenAt: nowIso(),
          }),
        );
        if (upd.letter == null) cellEntries.delete(key);
        else cellEntries.set(key, upd.letter);
        if (lobby) maybeFireWordLocked(lobby, upd.row, upd.column);
        maybeFireGameSolved();
        return;
      }

      case 'cellFocus': {
        // The bot answers a player's cellFocus by emitting its own
        // presenceUpdated at a randomly-chosen letter cell. That keeps
        // the overlay alive and visible to a single reviewer without a
        // second tab — every time the player focuses a cell, the bot
        // "looks somewhere" too. Bursts compress server-side because
        // the player's cellFocus is already debounced client-side at
        // 200 ms (`WebSocketGameClient`).
        const lobby = getLobby(lobbyId);
        if (!lobby || lobby.state !== 'IN_PROGRESS' || !lobby.game) return;
        const letterCells = lobby.game.puzzle.cells.filter((c) => c.kind === 'letter');
        if (letterCells.length === 0) return;
        const target = letterCells[Math.floor(Math.random() * letterCells.length)]!;
        const direction = Math.random() < 0.5 ? 'across' : 'down';
        client.send(
          JSON.stringify({
            type: 'presenceUpdated',
            sessionId: BOT_SESSION_ID,
            row: target.position.row,
            column: target.position.column,
            direction,
          }),
        );
        return;
      }

      case 'renameSelf': {
        const rn = parsed as unknown as { newPseudonym: string };
        // No per-connection sessionId stash; fall back to owner — the
        // most common rename target in a preview demo.
        const ownerSessionId = getLobby(lobbyId)?.ownerSessionId;
        if (!ownerSessionId) return;
        updateLobby(lobbyId, (current) => ({
          ...current,
          players: current.players.map((p) =>
            p.sessionId === ownerSessionId ? { ...p, pseudonym: rn.newPseudonym } : p,
          ),
        }));
        client.send(
          JSON.stringify({
            type: 'playerRenamed',
            sessionId: ownerSessionId,
            newPseudonym: rn.newPseudonym,
          }),
        );
        return;
      }

      case 'rotateCode': {
        // ADR-0029: owner-only rotation. Preview always accepts (the
        // owner is always the local player in MSW mode).
        if (!getLobby(lobbyId)) return;
        const newCode = generateLobbyCode();
        updateLobby(lobbyId, (current) => ({ ...current, code: newCode }));
        sendSnapshot();
        return;
      }

      case 'leaveLobby':
      default:
        return;
    }
  });

  // 4. Cleanup on close — fires even on abnormal terminations.
  client.addEventListener('close', () => {
    if (joinTimer) clearTimeout(joinTimer);
    if (tickTimer) clearTimeout(tickTimer);
    if (presenceTimer) clearTimeout(presenceTimer);
  });
});

export const gameWsHandler = lobbyWsHandler;

/**
 * Builder for `LobbySummary` wire fixtures. PR #9's tests
 * (`listMyLobbies` hook / route loader) override the default MSW
 * handler with `server.use(http.get(..., () => HttpResponse.json([
 * lobbySummaryFixture(...), ... ])))` to drive the "Mes parties" UI.
 * The default `id` is a valid 8-char base58 LobbyId per ADR-0020 §5
 * so the wire shape round-trips through brand assertions without
 * adjustment.
 */
export function lobbySummaryFixture(
  overrides: Partial<WireLobbySummary> = {},
): WireLobbySummary {
  return {
    id: '7Hk2pQrS',
    code: 'A2B3C4',
    state: 'IN_PROGRESS',
    gridConfig: { width: 15, height: 12 },
    playerCount: 2,
    lastActivityAt: '2026-05-10T18:00:00Z',
    progress: { solvedCells: 0, totalCells: 0 },
    ...overrides,
  };
}
