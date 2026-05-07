import { useEffect, useState } from 'react';
import { css } from 'styled-system/css';
import { ClockIcon } from '@/ui/components/icons';

// Toolbar timer pill — ADR-0005 §6. Sage at 12 % alpha background +
// sage text, clock icon, tabular numerals, pill-shaped.
//
// Three data modes, picked by the props passed:
//   * No props          — solo flow. Ticks from the mount instant.
//   * `startedAt`       — multiplayer flow. Ticks against the
//                         server-emitted `gameStarted.startedAt` ISO
//                         string so every client converges on the
//                         same elapsed value.
//   * `frozenAtMs`      — game ended. Renders the fixed duration and
//                         stops ticking.
//   * `fixedElapsedMs`  — storybook / deterministic tests.

const HOUR_MS = 60 * 60 * 1000;
const MINUTE_MS = 60 * 1000;
const SECOND_MS = 1000;

const twoDigitFormatter = new Intl.NumberFormat('fr-FR', {
  minimumIntegerDigits: 2,
  useGrouping: false,
});

function format(elapsedMs: number): string {
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

// Sage at 12 % alpha — the brief's prescribed pill background. Computed
// from the resolved `accent` token via `color-mix`; this keeps the alpha
// math at the CSS layer so a future theme-swap doesn't have to ship a
// pre-mixed RGBA. `color-mix(in srgb, …)` is supported in every browser
// the project targets (Chrome 111+, Firefox 113+, Safari 16.2+).
const pillStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  gap: '6px',
  paddingInline: '12px',
  paddingBlock: '4px',
  borderRadius: '999px',
  bg: 'color-mix(in srgb, token(colors.accent) 12%, transparent)',
  color: 'accent',
  fontFamily: 'body',
  fontWeight: 'medium',
  fontSize: 'sm',
  fontVariantNumeric: 'tabular-nums',
  lineHeight: '1',
  // The icon is sized via 1em.
  '& svg': { width: '14px', height: '14px' },
});

export interface TimerPillProps {
  // Server-emitted `gameStarted.startedAt` ISO timestamp. Drives the
  // multiplayer pill so every client converges on the same elapsed
  // value. Omit on solo to fall back to "tick from mount".
  readonly startedAt?: string;
  // Frozen duration in ms (multiplayer `gameSolved`). Renders a fixed
  // time and stops ticking.
  readonly frozenAtMs?: number;
  // Storybook / visual-test override. When set, displayed time is
  // deterministic regardless of mount instant or `startedAt`.
  readonly fixedElapsedMs?: number;
}

export function TimerPill({ startedAt, frozenAtMs, fixedElapsedMs }: TimerPillProps) {
  const [elapsedMs, setElapsedMs] = useState<number>(() => {
    if (fixedElapsedMs !== undefined) return fixedElapsedMs;
    if (frozenAtMs !== undefined) return frozenAtMs;
    if (startedAt !== undefined) return Date.now() - new Date(startedAt).getTime();
    return 0;
  });

  useEffect(() => {
    if (fixedElapsedMs !== undefined) {
      setElapsedMs(fixedElapsedMs);
      return;
    }
    if (frozenAtMs !== undefined) {
      setElapsedMs(frozenAtMs);
      return;
    }
    // The two live-tick modes share the same interval — only the
    // `startMs` reference differs. Solo: mount instant. Multiplayer:
    // server-emitted ISO timestamp.
    const startMs =
      startedAt !== undefined ? new Date(startedAt).getTime() : Date.now();
    setElapsedMs(Date.now() - startMs);
    const id = setInterval(() => {
      setElapsedMs(Date.now() - startMs);
    }, SECOND_MS);
    return () => {
      clearInterval(id);
    };
  }, [startedAt, frozenAtMs, fixedElapsedMs]);

  return (
    <span
      className={pillStyles}
      role="timer"
      aria-live="off"
      aria-label="temps écoulé"
      data-testid="puzzle-timer"
    >
      <ClockIcon />
      {format(elapsedMs)}
    </span>
  );
}
