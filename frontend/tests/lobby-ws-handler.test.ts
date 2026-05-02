import {
  afterAll,
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from 'vitest';
import { setupServer } from 'msw/node';

import { handlers } from '@/infrastructure/mocks/handlers';
import {
  __resetLobbyStore,
  BOT_PSEUDONYM,
  BOT_SESSION_ID,
} from '@/infrastructure/mocks/handlers/lobbyStore';

// Contract test for the MSW lobby WebSocket handler. Drives the
// connect → snapshot → join → bot-joins → start → cellUpdate cycle
// through a real `WebSocket` instance — MSW's `ws.link()` overrides
// the global ctor so the same code path the browser bundle exercises
// is what's under test here.
//
// Timers are virtualized with `vi.useFakeTimers` so the bot-join delay
// (3s) and the bot keystroke loop (4-8s) don't slow the suite down.
// Only at unit-test level — preview-deploy verification is out of scope.

const BASE = 'wss://game.test';
const sessionId = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b';
const pseudonym = 'Joueur 1234';

const server = setupServer(...handlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => {
  server.resetHandlers(...handlers);
  vi.useRealTimers();
});
afterAll(() => server.close());
beforeEach(() => {
  __resetLobbyStore();
});

// Creates a lobby via REST so the WS handler has something to look up
// in the store, then opens a real WebSocket to the same id. Returns
// both the lobby id and the open socket once the initial `lobbyState`
// snapshot has been received.
async function createLobbyAndConnect(): Promise<{
  lobbyId: string;
  socket: WebSocket;
  frames: unknown[];
}> {
  const create = await fetch('https://game.test/v1/lobbies', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ ownerSessionId: sessionId, ownerPseudonym: pseudonym }),
  });
  const body = (await create.json()) as { id: string };
  const lobbyId = body.id;

  const socket = new WebSocket(`${BASE}/v1/lobbies/${lobbyId}/ws`);
  const frames: unknown[] = [];
  socket.addEventListener('message', (event) => {
    const data = typeof event.data === 'string' ? event.data : '';
    try {
      frames.push(JSON.parse(data));
    } catch {
      // ignore non-JSON
    }
  });
  await new Promise<void>((resolve, reject) => {
    socket.addEventListener('open', () => resolve(), { once: true });
    socket.addEventListener('error', () => reject(new Error('socket error')), { once: true });
  });
  // Give the snapshot a microtask to land before the test inspects frames.
  await new Promise<void>((r) => setTimeout(r, 0));
  return { lobbyId, socket, frames };
}

describe('MSW lobby WebSocket handler', () => {
  it('emits a lobbyState snapshot on connect', async () => {
    const { socket, frames } = await createLobbyAndConnect();
    const snapshot = frames[0] as { type: string; players: Array<{ sessionId: string }>; state: string; game: unknown };
    expect(snapshot.type).toBe('lobbyState');
    expect(snapshot.state).toBe('WAITING');
    expect(snapshot.players).toHaveLength(1);
    expect(snapshot.players[0]?.sessionId).toBe(sessionId);
    expect(snapshot.game).toBeNull();
    socket.close();
  });

  it('echoes the joining player back as playerJoined', async () => {
    const { socket, frames } = await createLobbyAndConnect();
    socket.send(JSON.stringify({ type: 'joinLobby', sessionId, pseudonym }));
    await new Promise<void>((r) => setTimeout(r, 5));
    const joined = frames.find(
      (f): f is { type: string; sessionId: string; pseudonym: string } =>
        typeof f === 'object' && f !== null && (f as { type?: unknown }).type === 'playerJoined',
    );
    expect(joined).toBeDefined();
    expect(joined?.sessionId).toBe(sessionId);
    socket.close();
  });

  it('schedules a bot playerJoined ~3s after connect', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { socket, frames } = await createLobbyAndConnect();

    // Before the timer fires, no bot frame.
    expect(
      frames.find(
        (f): f is { type: string; sessionId: string } =>
          typeof f === 'object' && f !== null &&
          (f as { sessionId?: unknown }).sessionId === BOT_SESSION_ID,
      ),
    ).toBeUndefined();

    await vi.advanceTimersByTimeAsync(3_500);

    const botJoined = frames.find(
      (f): f is { type: string; sessionId: string; pseudonym: string } =>
        typeof f === 'object' && f !== null &&
        (f as { type?: unknown }).type === 'playerJoined' &&
        (f as { sessionId?: unknown }).sessionId === BOT_SESSION_ID,
    );
    expect(botJoined).toBeDefined();
    expect(botJoined?.pseudonym).toBe(BOT_PSEUDONYM);
    socket.close();
  });

  it('rebroadcasts a fresh lobbyState snapshot on setGridConfig', async () => {
    const { socket, frames } = await createLobbyAndConnect();
    frames.length = 0;
    socket.send(JSON.stringify({ type: 'setGridConfig', width: 7, height: 7 }));
    await new Promise<void>((r) => setTimeout(r, 5));
    const snapshot = frames.find(
      (f): f is { type: string; gridConfig: { width: number; height: number } } =>
        typeof f === 'object' && f !== null &&
        (f as { type?: unknown }).type === 'lobbyState',
    );
    expect(snapshot?.gridConfig).toEqual({ width: 7, height: 7 });
    socket.close();
  });

  it('emits gameStarted with a non-empty puzzle on startGame', async () => {
    const { socket, frames } = await createLobbyAndConnect();
    frames.length = 0;
    socket.send(JSON.stringify({ type: 'startGame' }));
    await new Promise<void>((r) => setTimeout(r, 5));
    const started = frames.find(
      (f): f is {
        type: string;
        puzzle: { width: number; cells: unknown[] };
        startedAt: string;
      } =>
        typeof f === 'object' && f !== null &&
        (f as { type?: unknown }).type === 'gameStarted',
    );
    expect(started).toBeDefined();
    expect(started?.puzzle.width).toBeGreaterThanOrEqual(5);
    expect(started?.puzzle.cells.length).toBeGreaterThan(0);
    expect(started?.startedAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    socket.close();
  });

  it('echoes a client cellUpdate as a server-stamped cellUpdated', async () => {
    const { socket, frames } = await createLobbyAndConnect();
    // Need to go IN_PROGRESS first so cellEntries tracking is meaningful.
    socket.send(JSON.stringify({ type: 'startGame' }));
    await new Promise<void>((r) => setTimeout(r, 5));
    frames.length = 0;
    socket.send(
      JSON.stringify({ type: 'cellUpdate', row: 0, column: 1, letter: 'A' }),
    );
    await new Promise<void>((r) => setTimeout(r, 5));
    const echoed = frames.find(
      (f): f is { type: string; row: number; column: number; letter: string; writtenAt: string } =>
        typeof f === 'object' && f !== null &&
        (f as { type?: unknown }).type === 'cellUpdated',
    );
    expect(echoed).toBeDefined();
    expect(echoed?.row).toBe(0);
    expect(echoed?.column).toBe(1);
    expect(echoed?.letter).toBe('A');
    expect(echoed?.writtenAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    socket.close();
  });
});
