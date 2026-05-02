import { useEffect, useState } from 'react';
import { css } from 'styled-system/css';

// Live elapsed-time display for an in-progress multiplayer game.
//
// Pure prop-driven; no `gameClient`/context dependency so it can be unit
// tested in isolation. The lobby route owns the wiring (a separate PR
// per Wave H plan): on `gameStarted` it renders `<Timer startedAt=...>`,
// and on `gameSolved` it freezes by passing `frozenAtMs={durationMs}`.
//
// Format rules (French numeric locale):
//   * < 1 hour  → MM:SS  (e.g. `04:32`)
//   * ≥ 1 hour  → HH:MM:SS (e.g. `01:23:45`)
// Padding uses `Intl.NumberFormat('fr-FR', { minimumIntegerDigits: 2 })`
// so the digit shaping respects the locale (no Latin-only assumption).
//
// Tick cadence is 1s — sub-second precision is overkill for a puzzle
// timer and would re-render every animation frame for no user benefit.
// The interval runs only while `frozenAtMs` is undefined; once frozen,
// the component renders the frozen value and never starts another
// interval, so a remount-after-solve cannot leak a tick.

export interface TimerProps {
  // ISO-8601 instant the server emitted in `gameStarted`. Parsed to a
  // numeric epoch on every tick (cheap; the elapsed math has to subtract
  // SOMETHING). We do not memoize the parsed value because `startedAt`
  // is a string prop — a stable reference across renders is the caller's
  // contract, and `Date.parse` on an ISO-8601 string is sub-microsecond.
  readonly startedAt: string;
  // When defined, the timer renders this fixed duration (ms) and stops
  // ticking. Set by the lobby route on `gameSolved` → `durationMs`.
  readonly frozenAtMs?: number;
}

const HOUR_MS = 60 * 60 * 1000;
const MINUTE_MS = 60 * 1000;
const SECOND_MS = 1000;

const twoDigitFormatter = new Intl.NumberFormat('fr-FR', {
  minimumIntegerDigits: 2,
  useGrouping: false,
});

function format(elapsedMs: number): string {
  // Clamp negative values (clock skew between server-emitted `startedAt`
  // and the client clock can briefly produce a negative delta on the
  // very first tick). Rendering `00:00` is friendlier than `-0:01`.
  const ms = Math.max(0, elapsedMs);
  const totalSeconds = Math.floor(ms / SECOND_MS);
  const hours = Math.floor(ms / HOUR_MS);
  const minutes = Math.floor((ms % HOUR_MS) / MINUTE_MS);
  const seconds = totalSeconds % 60;
  const mm = twoDigitFormatter.format(minutes);
  const ss = twoDigitFormatter.format(seconds);
  if (hours > 0) {
    const hh = twoDigitFormatter.format(hours);
    return `${hh}:${mm}:${ss}`;
  }
  return `${mm}:${ss}`;
}

const timerStyles = css({
  display: 'inline-block',
  fontFamily: 'body',
  // Tabular figures keep the digits column-aligned during ticks so the
  // timer doesn't dance left/right when a `1` swaps for a `0`.
  fontVariantNumeric: 'tabular-nums',
  fontSize: 'lg',
  fontWeight: 'bold',
  color: 'leaf.700',
  letterSpacing: '0.02em',
});

export function Timer({ startedAt, frozenAtMs }: TimerProps) {
  // We hold the elapsed value in state so React re-renders on each tick.
  // Initialize from the same `Date.now() - startedAt` formula that the
  // interval uses, so the first paint shows the correct value (rather
  // than `00:00` for a tick before the first interval fires).
  const [elapsedMs, setElapsedMs] = useState<number>(() =>
    frozenAtMs ?? Date.now() - new Date(startedAt).getTime(),
  );

  useEffect(() => {
    if (frozenAtMs !== undefined) {
      // Frozen render — just sync state once and don't start a tick.
      setElapsedMs(frozenAtMs);
      return;
    }
    const startMs = new Date(startedAt).getTime();
    // Sync once immediately so a remount mid-game shows the right value
    // before the first interval fires (1s later).
    setElapsedMs(Date.now() - startMs);
    const id = setInterval(() => {
      setElapsedMs(Date.now() - startMs);
    }, SECOND_MS);
    return () => {
      clearInterval(id);
    };
  }, [startedAt, frozenAtMs]);

  return (
    <span
      className={timerStyles}
      role="timer"
      aria-live="off"
      aria-label="temps écoulé"
      data-testid="game-timer"
    >
      {format(elapsedMs)}
    </span>
  );
}
