import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createWebSocketGameClient } from '@/infrastructure';
import type { GameEvent } from '@/application/game';
import type { Letter, LobbyId, Pseudonym, SessionId } from '@/domain/game';

// Mock socket exposing the structural surface the adapter consumes
// (`onopen`, `onmessage`, `onerror`, `onclose`, `send`, `close`,
// `readyState`, `url`). External boundary, mocked per ADR-0001.
class MockWebSocket {
  static instances: MockWebSocket[] = [];
  static reset() { MockWebSocket.instances = []; }

  onopen: ((ev: Event) => unknown) | null = null;
  onmessage: ((ev: MessageEvent) => unknown) | null = null;
  onerror: ((ev: Event) => unknown) | null = null;
  onclose: ((ev: CloseEvent) => unknown) | null = null;
  // Spec: 0 = CONNECTING, 1 = OPEN, 3 = CLOSED.
  readyState = 0;
  sent: string[] = [];
  closeCalls: Array<{ code?: number; reason?: string }> = [];
  url: string;

  constructor(url: string) {
    this.url = url;
    MockWebSocket.instances.push(this);
  }

  send(data: string) { this.sent.push(data); }
  close(code?: number, reason?: string) {
    this.closeCalls.push({ code, reason });
    this.readyState = 3;
    this.onclose?.(new CloseEvent('close', { code: code ?? 1000 }));
  }

  // Drive the adapter from the "transport" side.
  emitOpen() { this.readyState = 1; this.onopen?.(new Event('open')); }
  emitMessage(data: unknown) {
    const payload = typeof data === 'string' ? data : JSON.stringify(data);
    this.onmessage?.(new MessageEvent('message', { data: payload }));
  }
  emitError() { this.onerror?.(new Event('error')); }
}

const lobbyId = '7gQ2xK9p' as LobbyId;
const sessionId = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b' as SessionId;
const pseudonym = 'Joueur 1234' as Pseudonym;

const makeClient = () =>
  createWebSocketGameClient({
    wsBaseUrl: 'wss://game.test',
    WebSocketCtor: MockWebSocket as unknown as new (url: string) => WebSocket,
  });

const connectAndOpen = async () => {
  const client = makeClient();
  const connected = client.connect({ lobbyId, sessionId, pseudonym });
  MockWebSocket.instances[0]!.emitOpen();
  await connected;
  return { client, ws: MockWebSocket.instances[0]! };
};

beforeEach(() => MockWebSocket.reset());
afterEach(() => vi.restoreAllMocks());

describe('WebSocketGameClient.connect', () => {
  it('opens at /v1/lobbies/:lobbyId/ws and sends joinLobby on open', async () => {
    const client = makeClient();
    const connected = client.connect({ lobbyId, sessionId, pseudonym });
    const ws = MockWebSocket.instances[0]!;
    expect(ws.url).toBe('wss://game.test/v1/lobbies/7gQ2xK9p/ws');

    ws.emitOpen();
    await expect(connected).resolves.toBeUndefined();

    expect(JSON.parse(ws.sent[0]!)).toEqual({ type: 'joinLobby', sessionId, pseudonym });
  });

  it('rejects when the socket errors before opening', async () => {
    const client = makeClient();
    const connected = client.connect({ lobbyId, sessionId, pseudonym });
    MockWebSocket.instances[0]!.emitError();
    await expect(connected).rejects.toThrow(/connect failed/);
  });
});

describe('WebSocketGameClient client→server frames', () => {
  it('cellUpdate sends {type, row, column, letter} matching AsyncAPI CellUpdatePayload', async () => {
    const { client, ws } = await connectAndOpen();
    client.cellUpdate(2, 5, 'P' as Letter);
    client.cellUpdate(0, 0, null);

    // index 0 is the joinLobby handshake; cellUpdates start at 1.
    expect(JSON.parse(ws.sent[1]!)).toEqual({ type: 'cellUpdate', row: 2, column: 5, letter: 'P' });
    expect(JSON.parse(ws.sent[2]!)).toEqual({ type: 'cellUpdate', row: 0, column: 0, letter: null });
  });

  it('owner-only and rename frames serialize to AsyncAPI shapes', async () => {
    const { client, ws } = await connectAndOpen();
    client.setGridConfig({ width: 7, height: 7 });
    client.startGame();
    client.renameSelf('Alice' as Pseudonym);
    client.leaveLobby();

    expect(JSON.parse(ws.sent[1]!)).toEqual({ type: 'setGridConfig', width: 7, height: 7 });
    expect(JSON.parse(ws.sent[2]!)).toEqual({ type: 'startGame' });
    expect(JSON.parse(ws.sent[3]!)).toEqual({ type: 'renameSelf', newPseudonym: 'Alice' });
    expect(JSON.parse(ws.sent[4]!)).toEqual({ type: 'leaveLobby' });
  });

  it('throws when called before the socket is open', () => {
    const client = makeClient();
    expect(() => client.cellUpdate(0, 0, null)).toThrow(/not open/);
  });
});

describe('WebSocketGameClient.subscribe', () => {
  it('dispatches incoming lobbyState frames to handlers', async () => {
    const { client, ws } = await connectAndOpen();
    const events: GameEvent[] = [];
    const unsub = client.subscribe((e) => events.push(e));

    ws.emitMessage({
      type: 'lobbyState',
      players: [{ sessionId, pseudonym, joinedAt: '2026-05-02T15:30:00Z' }],
      ownerSessionId: sessionId,
      state: 'WAITING',
      gridConfig: { width: 7, height: 7 },
      game: null,
    });

    expect(events).toHaveLength(1);
    const event = events[0]!;
    if (event.type !== 'lobbyState') throw new Error('expected lobbyState');
    expect(event.players).toHaveLength(1);
    expect(event.gridConfig).toEqual({ width: 7, height: 7 });
    unsub();
  });

  it('returns an Unsubscribe that detaches the handler', async () => {
    const { client, ws } = await connectAndOpen();
    const events: GameEvent[] = [];
    const unsub = client.subscribe((e) => events.push(e));
    unsub();
    ws.emitMessage({ type: 'playerLeft', sessionId });
    expect(events).toHaveLength(0);
  });

  it('maps server `error` frames into GameErrorEvent with errorType preserved', async () => {
    const { client, ws } = await connectAndOpen();
    const events: GameEvent[] = [];
    client.subscribe((e) => events.push(e));

    ws.emitMessage({
      type: 'error',
      errorType: 'https://bliss.example/errors/lobby-full',
      title: 'Lobby is full',
      detail: 'This lobby already has 8 players.',
      status: 409,
    });

    const event = events[0]!;
    if (event.type !== 'error') throw new Error('expected error event');
    expect(event.errorType).toBe('https://bliss.example/errors/lobby-full');
    expect(event.title).toBe('Lobby is full');
    expect(event.status).toBe(409);
  });

  it('drops unknown server frame types with a console.warn', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { client, ws } = await connectAndOpen();
    const events: GameEvent[] = [];
    client.subscribe((e) => events.push(e));

    ws.emitMessage({ type: 'futureMessage', meaningOfLife: 42 });

    expect(events).toHaveLength(0);
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('futureMessage'));
  });
});

describe('WebSocketGameClient.disconnect', () => {
  it('closes the socket with code 1000', async () => {
    const { client, ws } = await connectAndOpen();
    client.disconnect();
    expect(ws.closeCalls).toEqual([{ code: 1000, reason: undefined }]);
    expect(ws.readyState).toBe(3);
  });

  it('is a no-op when not connected', () => {
    const client = makeClient();
    expect(() => client.disconnect()).not.toThrow();
  });
});
