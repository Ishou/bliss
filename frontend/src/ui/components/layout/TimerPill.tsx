import { useEffect, useState } from 'react';
import { css } from 'styled-system/css';
import { ClockIcon } from '@/ui/components/icons';

// Solo-mode toolbar timer pill — ADR-0005 §6.
//
// Sage at 12% alpha background + sage text, clock icon, tabular numerals,
// pill-shaped. Format mirrors `lobby/Timer.tsx` (MM:SS or HH:MM:SS) but
// the data path is local: ticks from the moment this component mounts.
// The lobby timer is server-driven (`gameStarted` event); this one is
// client-driven and intentionally trivial — it has no role in scoring,
// it's a UI affordance for the solo flow.

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
  // Optional override — primarily for storybook / visual tests so the
  // displayed time is deterministic. When omitted, the pill ticks from
  // the mount instant.
  readonly fixedElapsedMs?: number;
}

export function TimerPill({ fixedElapsedMs }: TimerPillProps) {
  const [elapsedMs, setElapsedMs] = useState<number>(() => fixedElapsedMs ?? 0);

  useEffect(() => {
    if (fixedElapsedMs !== undefined) {
      setElapsedMs(fixedElapsedMs);
      return;
    }
    const startMs = Date.now();
    const id = setInterval(() => {
      setElapsedMs(Date.now() - startMs);
    }, SECOND_MS);
    return () => {
      clearInterval(id);
    };
  }, [fixedElapsedMs]);

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
