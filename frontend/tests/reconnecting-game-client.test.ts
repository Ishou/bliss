import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createReconnectingGameClient } from '@/infrastructure';
import type {
  ConnectionState,
  GameClient,
  GameEvent,
  Unsubscribe,
} from '@/application/game';
import type { LobbyId, Pseudonym, SessionId } from '@/domain/game';

// In-memory fake of the inner GameClient. Lets tests drive transport
// transitions deterministically without standing up a real WebSocket.
// The `dispatchConnectionState` helper is the only way the fake exits
// the initial 'disconnected' state — tests build the sequence step by
// step so the wrapper's behaviour at each transition is observable.
function makeFakeInnerClient() {
  const connectCalls: Array<{ lobbyId: LobbyId; code?: string }> = [];
  const disconnectCalls = { count: 0 };
  const connectionSubscribers = new Set<(s: ConnectionState) => void>();
  let connectionState: ConnectionState = 'disconnected';
  let pendingConnect: { resolve: () => void; reject: (e: Error) => void } | null = null;

  const setConnectionState = (next: ConnectionState) => {
    connectionState = next;
    for (const h of [...connectionSubscribers]) h(next);
  };

  const inner: GameClient = {
    connect: (args) => {
      connectCalls.push({ lobbyId: args.lobbyId, code: args.code });
      setConnectionState('connecting');
      return new Promise<void>((resolve, reject) => {
        pendingConnect = { resolve, reject };
      });
    },
    joinLobby: () => {},
    renameSelf: () => {},
    setGridConfig: () => {},
    startGame: () => {},
    cellUpdate: () => {},
    cellFocus: () => {},
    leaveLobby: () => {},
    rotateCode: () => {},
    disconnect: () => {
      disconnectCalls.count += 1;
      setConnectionState('disconnected');
    },
    subscribe: () => () => undefined,
    subscribeConnectionState: (handler) => {
      connectionSubscribers.add(handler);
      handler(connectionState);
      return () => { connectionSubscribers.delete(handler); };
    },
  };

  return {
    inner,
    connectCalls,
    disconnectCalls,
    // Test helpers — pair with each pending inner.connect() promise.
    resolveOpen: () => {
      pendingConnect?.resolve();
      pendingConnect = null;
      setConnectionState('connected');
    },
    rejectAndClose: (err: Error) => {
      pendingConnect?.reject(err);
      pendingConnect = null;
      // Real WebSocket adapter: onerror is followed by onclose, which
      // sets 'disconnected'. Mirror that here.
      setConnectionState('disconnected');
    },
    // Mid-session drop — fires onclose without resolving a pending
    // connect promise.
    drop: () => { setConnectionState('disconnected'); },
    getConnectionState: () => connectionState,
  };
}

const collectStates = (client: GameClient): { states: ConnectionState[]; unsubscribe: Unsubscribe } => {
  const states: ConnectionState[] = [];
  const unsubscribe = client.subscribeConnectionState((s) => { states.push(s); });
  return { states, unsubscribe };
};

const lobbyId = '7gQ2xK9p' as LobbyId;
const sessionId = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b' as SessionId;
const pseudonym = 'Joueur 1234' as Pseudonym;

const connectArgs = { lobbyId, sessionId, pseudonym };

beforeEach(() => { vi.useFakeTimers(); });
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('ReconnectingGameClient', () => {
  it('proxies connect() to the inner client and surfaces "connected" on success', async () => {
    const fake = makeFakeInnerClient();
    const client = createReconnectingGameClient({ inner: fake.inner });
    const { states } = collectStates(client);

    const p = client.connect(connectArgs);
    expect(fake.connectCalls).toEqual([{ lobbyId, code: undefined }]);
    fake.resolveOpen();
    await p;

    // The wrapper primes with the initial 'disconnected' and then mirrors
    // the inner client's lifecycle: connecting → connected.
    expect(states).toEqual(['disconnected', 'connecting', 'connected']);
  });

  it('schedules a reconnect with backoff after an involuntary drop', async () => {
    const fake = makeFakeInnerClient();
    const client = createReconnectingGameClient({
      inner: fake.inner,
      baseDelayMs: 500,
      jitterRatio: 0, // deterministic timing
    });
    const { states } = collectStates(client);

    const p = client.connect(connectArgs);
    fake.resolveOpen();
    await p;
    expect(states.at(-1)).toBe('connected');

    // Mid-session drop.
    fake.drop();
    // Wrapper transitions to 'reconnecting' immediately — visible chrome
    // before the timer fires.
    expect(states.at(-1)).toBe('reconnecting');

    // Within the base delay window the wrapper has NOT called connect again.
    expect(fake.connectCalls.length).toBe(1);
    await vi.advanceTimersByTimeAsync(499);
    expect(fake.connectCalls.length).toBe(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(fake.connectCalls.length).toBe(2);
  });

  it('uses exponential backoff capped at maxDelayMs across consecutive failed attempts', async () => {
    const fake = makeFakeInnerClient();
    const client = createReconnectingGameClient({
      inner: fake.inner,
      baseDelayMs: 500,
      maxDelayMs: 4000,
      maxAttempts: 10,
      jitterRatio: 0,
    });
    collectStates(client);

    // Initial connect succeeds so we enter the reconnect loop with a
    // healthy session, then every subsequent reconnect FAILS so the
    // attempt counter grows monotonically and the delays exhibit pure
    // exponential growth (capped at maxDelayMs).
    const p = client.connect(connectArgs);
    fake.resolveOpen();
    await p;

    const expectedDelays = [500, 1000, 2000, 4000, 4000];
    // First drop transitions to 'reconnecting' — no inner.connect yet.
    fake.drop();
    let priorConnects = 1;
    for (const delay of expectedDelays) {
      await vi.advanceTimersByTimeAsync(delay - 1);
      expect(fake.connectCalls.length).toBe(priorConnects);
      await vi.advanceTimersByTimeAsync(1);
      priorConnects += 1;
      expect(fake.connectCalls.length).toBe(priorConnects);
      // Reject the in-flight attempt — fake emits 'disconnected' which
      // schedules the next attempt with the next exponential delay.
      fake.rejectAndClose(new Error('still down'));
    }
  });

  it('resets the attempt counter after a successful reconnect', async () => {
    const fake = makeFakeInnerClient();
    const client = createReconnectingGameClient({
      inner: fake.inner,
      baseDelayMs: 500,
      jitterRatio: 0,
    });
    collectStates(client);

    const p = client.connect(connectArgs);
    fake.resolveOpen();
    await p;

    // Drop, recover after first attempt — next drop should use base delay again.
    fake.drop();
    await vi.advanceTimersByTimeAsync(500);
    expect(fake.connectCalls.length).toBe(2);
    fake.resolveOpen();

    fake.drop();
    await vi.advanceTimersByTimeAsync(499);
    expect(fake.connectCalls.length).toBe(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(fake.connectCalls.length).toBe(3);
  });

  it('gives up after maxAttempts and emits a terminal "disconnected"', async () => {
    const fake = makeFakeInnerClient();
    const client = createReconnectingGameClient({
      inner: fake.inner,
      baseDelayMs: 500,
      maxAttempts: 2,
      jitterRatio: 0,
    });
    const { states } = collectStates(client);

    const p = client.connect(connectArgs);
    fake.resolveOpen();
    await p;
    fake.drop();
    // attempt 1
    await vi.advanceTimersByTimeAsync(500);
    expect(fake.connectCalls.length).toBe(2);
    fake.rejectAndClose(new Error('boom'));
    // attempt 2 — last one
    await vi.advanceTimersByTimeAsync(1000);
    expect(fake.connectCalls.length).toBe(3);
    fake.rejectAndClose(new Error('boom'));
    // No further attempts; wrapper has emitted terminal 'disconnected'.
    await vi.advanceTimersByTimeAsync(60_000);
    expect(fake.connectCalls.length).toBe(3);
    expect(states.at(-1)).toBe('disconnected');
  });

  it('does NOT reconnect after a voluntary disconnect()', async () => {
    const fake = makeFakeInnerClient();
    const client = createReconnectingGameClient({
      inner: fake.inner,
      baseDelayMs: 500,
      jitterRatio: 0,
    });
    const { states } = collectStates(client);

    const p = client.connect(connectArgs);
    fake.resolveOpen();
    await p;

    client.disconnect();
    expect(fake.disconnectCalls.count).toBe(1);
    expect(states.at(-1)).toBe('disconnected');

    await vi.advanceTimersByTimeAsync(60_000);
    expect(fake.connectCalls.length).toBe(1);
  });

  it('cancels a pending retry and does not reconnect when disconnect() is called during reconnecting state', async () => {
    const fake = makeFakeInnerClient();
    const client = createReconnectingGameClient({
      inner: fake.inner,
      baseDelayMs: 500,
      jitterRatio: 0,
    });
    const { states } = collectStates(client);

    const p = client.connect(connectArgs);
    fake.resolveOpen();
    await p;

    fake.drop();
    expect(states.at(-1)).toBe('reconnecting');

    client.disconnect();
    expect(states.at(-1)).toBe('disconnected');
    expect(fake.disconnectCalls.count).toBe(1);

    await vi.advanceTimersByTimeAsync(60_000);
    expect(fake.connectCalls.length).toBe(1); // no retry fired
  });

  it('forwards write-side methods straight through to the inner client', async () => {
    const fake = makeFakeInnerClient();
    const sentCellUpdates: Array<{ row: number; col: number; letter: string | null }> = [];
    const startGameCalls = { count: 0 };
    const innerOverride: GameClient = {
      ...fake.inner,
      cellUpdate: (row, column, letter) => {
        sentCellUpdates.push({ row, col: column, letter: letter as unknown as string | null });
      },
      startGame: () => { startGameCalls.count += 1; },
    };
    const client = createReconnectingGameClient({ inner: innerOverride });

    client.cellUpdate(1, 2, 'A' as never);
    client.startGame();

    expect(sentCellUpdates).toEqual([{ row: 1, col: 2, letter: 'A' }]);
    expect(startGameCalls.count).toBe(1);
  });

  it('replays the same connect args on each reconnect attempt (including optional code)', async () => {
    const fake = makeFakeInnerClient();
    const client = createReconnectingGameClient({
      inner: fake.inner,
      baseDelayMs: 500,
      jitterRatio: 0,
    });

    const p = client.connect({ ...connectArgs, code: 'A2B3C4' });
    fake.resolveOpen();
    await p;
    fake.drop();
    await vi.advanceTimersByTimeAsync(500);

    expect(fake.connectCalls).toEqual([
      { lobbyId, code: 'A2B3C4' },
      { lobbyId, code: 'A2B3C4' },
    ]);
  });

  it('proxies event subscriptions to the inner client', () => {
    const subscribers = new Set<(e: GameEvent) => void>();
    const inner: GameClient = {
      ...makeFakeInnerClient().inner,
      subscribe: (h) => { subscribers.add(h); return () => { subscribers.delete(h); }; },
    };
    const client = createReconnectingGameClient({ inner });
    const received: GameEvent[] = [];
    const off = client.subscribe((e) => received.push(e));

    for (const s of subscribers) {
      s({ type: 'error', errorType: 'x', title: 't' });
    }
    expect(received).toHaveLength(1);
    off();
    for (const s of subscribers) {
      s({ type: 'error', errorType: 'x', title: 't' });
    }
    expect(received).toHaveLength(1);
  });
});
