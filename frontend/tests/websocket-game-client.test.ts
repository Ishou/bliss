import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createWebSocketGameClient } from '@/infrastructure';
import type { ConnectionState, GameEvent } from '@/application/game';
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

describe('WebSocketGameClient.cellFocus debounce', () => {
  // ADR-0018 §"Presence" pins the outbound cadence at 5 Hz / 200 ms.
  // The adapter is the single source of truth for the debounce so
  // every consumer benefits without re-implementing the timer.
  it('collapses a burst of focus changes within 200 ms into one outbound frame carrying the latest tuple', async () => {
    vi.useFakeTimers();
    try {
      const client = makeClient();
      const connected = client.connect({ lobbyId, sessionId, pseudonym });
      MockWebSocket.instances[0]!.emitOpen();
      await connected;
      const ws = MockWebSocket.instances[0]!;
      // Three rapid focus changes within the window — only the last
      // tuple should reach the wire after the timer fires.
      client.cellFocus(0, 0, 'across');
      client.cellFocus(1, 2, 'across');
      client.cellFocus(3, 4, 'down');
      // Before the window elapses, only the join handshake (index 0)
      // is on the wire — no cellFocus frame yet.
      expect(ws.sent.length).toBe(1);
      vi.advanceTimersByTime(200);
      expect(ws.sent.length).toBe(2);
      expect(JSON.parse(ws.sent[1]!)).toEqual({
        type: 'cellFocus',
        row: 3,
        column: 4,
        direction: 'down',
      });
    } finally {
      vi.useRealTimers();
    }
  });

  it('forwards a `null` focus tuple verbatim so peers can drop the cursor', async () => {
    vi.useFakeTimers();
    try {
      const client = makeClient();
      const connected = client.connect({ lobbyId, sessionId, pseudonym });
      MockWebSocket.instances[0]!.emitOpen();
      await connected;
      const ws = MockWebSocket.instances[0]!;
      client.cellFocus(null, null, null);
      vi.advanceTimersByTime(200);
      expect(JSON.parse(ws.sent[1]!)).toEqual({
        type: 'cellFocus',
        row: null,
        column: null,
        direction: null,
      });
    } finally {
      vi.useRealTimers();
    }
  });

  it('drops a pending cellFocus on disconnect so the timer does not fire on a closed socket', async () => {
    vi.useFakeTimers();
    try {
      const client = makeClient();
      const connected = client.connect({ lobbyId, sessionId, pseudonym });
      MockWebSocket.instances[0]!.emitOpen();
      await connected;
      const ws = MockWebSocket.instances[0]!;
      client.cellFocus(2, 2, 'across');
      client.disconnect();
      vi.advanceTimersByTime(500);
      // The only frame that landed before disconnect was the join
      // handshake — the pending cellFocus was discarded with the
      // pending timer.
      expect(ws.sent.length).toBe(1);
    } finally {
      vi.useRealTimers();
    }
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

  it('dispatches incoming presenceUpdated frames to handlers', async () => {
    const { client, ws } = await connectAndOpen();
    const events: GameEvent[] = [];
    client.subscribe((e) => events.push(e));
    ws.emitMessage({
      type: 'presenceUpdated',
      sessionId,
      row: 2,
      column: 3,
      direction: 'across',
    });
    const event = events[0]!;
    if (event.type !== 'presenceUpdated') throw new Error('expected presenceUpdated');
    expect(event.row).toBe(2);
    expect(event.column).toBe(3);
    expect(event.direction).toBe('across');
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

describe('WebSocketGameClient.subscribeConnectionState', () => {
  it('primes the handler with the current state and emits transitions', async () => {
    const client = makeClient();
    const states: ConnectionState[] = [];
    const unsub = client.subscribeConnectionState((s) => states.push(s));
    // Synchronous priming call — a freshly-mounted banner gets the
    // current state before any transition fires.
    expect(states).toEqual(['disconnected']);

    const connected = client.connect({ lobbyId, sessionId, pseudonym });
    expect(states).toEqual(['disconnected', 'connecting']);
    const ws = MockWebSocket.instances[0]!;
    ws.emitOpen();
    await connected;
    expect(states).toEqual(['disconnected', 'connecting', 'connected']);

    ws.close(1006, 'abnormal');
    expect(states).toEqual([
      'disconnected', 'connecting', 'connected', 'disconnected',
    ]);
    unsub();
  });

  it('returns an Unsubscribe that detaches the handler', async () => {
    const { client, ws } = await connectAndOpen();
    const states: ConnectionState[] = [];
    const unsub = client.subscribeConnectionState((s) => states.push(s));
    states.length = 0; // discard the priming call
    unsub();
    ws.close(1000);
    expect(states).toEqual([]);
  });

  it('does not re-emit when the state has not actually changed', () => {
    const client = makeClient();
    const states: ConnectionState[] = [];
    client.subscribeConnectionState((s) => states.push(s));
    states.length = 0;
    // `disconnect()` on a never-connected client is a no-op (covered
    // elsewhere); state stays `disconnected` so subscribers must
    // not see a duplicate emission.
    client.disconnect();
    expect(states).toEqual([]);
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

describe('WebSocketGameClient reconnect race', () => {
  // React StrictMode (dev) double-mounts useEffect, so the lobby route
  // calls connect → disconnect → connect synchronously. The original
  // `onclose` handler unconditionally cleared the outer socket ref,
  // which nuked the freshly-assigned new socket when the old one's
  // close event finally fired. Caused the multiplayer e2e to fail with
  // "socket is not open" on every Démarrer click.
  it('keeps the new socket usable when an old socket\'s onclose fires after a fresh connect()', async () => {
    const client = makeClient();
    // First mount: connect, open, then synchronously disconnect (the
    // strict-mode cleanup path).
    const firstConnect = client.connect({ lobbyId, sessionId, pseudonym });
    const ws1 = MockWebSocket.instances[0]!;
    ws1.emitOpen();
    await firstConnect;

    // disconnect() calls ws1.close(); the mock fires onclose
    // synchronously inside close(). We then immediately reconnect.
    client.disconnect();

    const secondConnect = client.connect({ lobbyId, sessionId, pseudonym });
    const ws2 = MockWebSocket.instances[1]!;
    ws2.emitOpen();
    await secondConnect;

    // The new socket should accept frames. Without the fix this
    // throws "socket is not open" because the lingering close from
    // ws1 (or, in tests, the synchronous one) wiped the ref.
    expect(() => client.cellUpdate(0, 1, 'D' as Letter)).not.toThrow();
    expect(JSON.parse(ws2.sent[ws2.sent.length - 1]!)).toEqual({
      type: 'cellUpdate',
      row: 0,
      column: 1,
      letter: 'D',
    });
  });
});
