import { act, renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type {
  ConnectionLostEvent,
  GameEvent,
  IdleEvent,
  PresenceUpdatedEvent,
  TypingEvent,
  Unsubscribe,
} from '@/application/game';
import type { SessionId } from '@/domain/game';
import { usePresenceState } from '@/ui/components/grid/usePresenceState';

const SESSION_A = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c' as SessionId;
const SESSION_LOCAL = 'bbbb2222-7a2c-7c9e-8f1a-9b2d3e4f5a6c' as SessionId;

interface FakeStream {
  readonly subscribe: (handler: (e: GameEvent) => void) => Unsubscribe;
  readonly dispatch: (e: GameEvent) => void;
}

const makeFakeStream = (): FakeStream => {
  const subs = new Set<(e: GameEvent) => void>();
  return {
    subscribe: (h) => {
      subs.add(h);
      return () => {
        subs.delete(h);
      };
    },
    dispatch: (event) => {
      for (const s of [...subs]) s(event);
    },
  };
};

const typing = (sessionId: SessionId, value: boolean): TypingEvent => ({
  type: 'typing',
  sessionId,
  typing: value,
});
const idle = (sessionId: SessionId, value: boolean): IdleEvent => ({
  type: 'idle',
  sessionId,
  idle: value,
});
const connectionLost = (sessionId: SessionId): ConnectionLostEvent => ({
  type: 'connectionLost',
  sessionId,
});
const presenceUpdated = (sessionId: SessionId): PresenceUpdatedEvent => ({
  type: 'presenceUpdated',
  sessionId,
  row: 0,
  column: 1,
  direction: 'across',
});

describe('usePresenceState', () => {
  it('tracks the typing edge for a remote session', () => {
    const stream = makeFakeStream();
    const { result } = renderHook(() =>
      usePresenceState(stream.subscribe, SESSION_LOCAL),
    );

    act(() => stream.dispatch(typing(SESSION_A, true)));
    expect(result.current.get(SESSION_A)?.typing).toBe(true);

    act(() => stream.dispatch(typing(SESSION_A, false)));
    expect(result.current.get(SESSION_A)?.typing).toBe(false);
  });

  it('tracks the idle edge independently of typing', () => {
    const stream = makeFakeStream();
    const { result } = renderHook(() =>
      usePresenceState(stream.subscribe, SESSION_LOCAL),
    );

    act(() => {
      stream.dispatch(typing(SESSION_A, true));
      stream.dispatch(idle(SESSION_A, true));
    });
    const state = result.current.get(SESSION_A);
    expect(state?.typing).toBe(true);
    expect(state?.idle).toBe(true);
  });

  it('marks connectionLost on disconnect and clears it on the next presenceUpdated', () => {
    const stream = makeFakeStream();
    const { result } = renderHook(() =>
      usePresenceState(stream.subscribe, SESSION_LOCAL),
    );

    act(() => stream.dispatch(connectionLost(SESSION_A)));
    expect(result.current.get(SESSION_A)?.connectionLost).toBe(true);

    act(() => stream.dispatch(presenceUpdated(SESSION_A)));
    expect(result.current.get(SESSION_A)?.connectionLost).toBe(false);
  });

  it('drops events whose sessionId matches currentSessionId', () => {
    const stream = makeFakeStream();
    const { result } = renderHook(() =>
      usePresenceState(stream.subscribe, SESSION_LOCAL),
    );

    act(() => {
      stream.dispatch(typing(SESSION_LOCAL, true));
      stream.dispatch(idle(SESSION_LOCAL, true));
      stream.dispatch(connectionLost(SESSION_LOCAL));
    });

    expect(result.current.has(SESSION_LOCAL)).toBe(false);
  });

  it('returns the same Map reference when an event is a no-op (e.g. idle false on an unidled session)', () => {
    const stream = makeFakeStream();
    const { result } = renderHook(() =>
      usePresenceState(stream.subscribe, SESSION_LOCAL),
    );

    // Establish a baseline so the session is in the map.
    act(() => stream.dispatch(typing(SESSION_A, true)));
    const before = result.current;

    // A presenceUpdated when connectionLost is already false should be
    // a no-op for the connectionLost field, leaving the state unchanged.
    act(() => stream.dispatch(presenceUpdated(SESSION_A)));
    expect(result.current).toBe(before);
  });
});
