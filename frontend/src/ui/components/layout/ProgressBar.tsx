import { css } from 'styled-system/css';

// Puzzle progress bar — ADR-0005 §6 component spec.
//
// Track in `surfaceElevated` (raised neutral), fill in sage. A label row
// above pairs muted "Progression" on the left with a sage "X / Y cases"
// count on the right. Spec calls for 4–5 px track height; we ship 5 px.
// Fill animates 220 ms ease-out, matching the brief's motion table.

const wrapperStyles = css({
  display: 'flex',
  flexDirection: 'column',
  gap: '6px',
  width: '100%',
});

const labelRowStyles = css({
  display: 'flex',
  alignItems: 'baseline',
  justifyContent: 'space-between',
  fontFamily: 'body',
  fontSize: 'sm',
  lineHeight: '1',
});

const labelMutedStyles = css({
  color: 'fgMuted',
  fontWeight: 'regular',
});

const labelCountStyles = css({
  color: 'accent',
  fontWeight: 'medium',
  fontVariantNumeric: 'tabular-nums',
});

const trackStyles = css({
  position: 'relative',
  width: '100%',
  height: '5px',
  borderRadius: '999px',
  bg: 'surfaceElevated',
  overflow: 'hidden',
});

const fillStyles = css({
  position: 'absolute',
  inset: '0 auto 0 0',
  height: '100%',
  bg: 'accent',
  borderRadius: '999px',
  transition: 'width 220ms ease-out',
});

// Rendered before fillStyles so sage paints on top, hiding sub-pixel seams.
const pendingFillStyles = css({
  position: 'absolute',
  top: 0,
  bottom: 0,
  height: '100%',
  bg: 'border',
  transition: 'width 220ms ease-out, left 220ms ease-out',
});

export interface ProgressBarProps {
  readonly value: number;
  readonly total: number;
  readonly label?: string;
  readonly pending?: number;
}

export function ProgressBar({ value, total, label = 'Progression', pending = 0 }: ProgressBarProps) {
  // Defensive clamp: if `total` is 0 the puzzle has no letter cells (an
  // edge case that should never reach here in practice); render an empty
  // bar rather than a NaN width.
  const safeTotal = Math.max(total, 0);
  const safeValue = Math.max(0, Math.min(value, safeTotal));
  // Clamp to remaining track so gray never overflows past 100%.
  const safePending = Math.max(0, Math.min(pending, safeTotal - safeValue));
  const pct = safeTotal === 0 ? 0 : (safeValue / safeTotal) * 100;
  const pendingPct = safeTotal === 0 ? 0 : (safePending / safeTotal) * 100;
  return (
    <div className={wrapperStyles} data-testid="puzzle-progress">
      <div className={labelRowStyles}>
        <span className={labelMutedStyles}>{label}</span>
        <span className={labelCountStyles}>
          {safeValue} / {safeTotal} cases
        </span>
      </div>
      <div
        className={trackStyles}
        role="progressbar"
        aria-label={label}
        aria-valuenow={safeValue}
        aria-valuemin={0}
        aria-valuemax={safeTotal}
      >
        {/* Pending FIRST so the sage paints on top — kills sub-pixel seams. */}
        <div
          className={pendingFillStyles}
          data-testid="puzzle-progress-pending"
          style={{ left: `${pct}%`, width: `${pendingPct}%` }}
        />
        <div className={fillStyles} style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}
