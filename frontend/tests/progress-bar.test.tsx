import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { ProgressBar } from '@/ui/components/layout';

const PENDING_TESTID = 'puzzle-progress-pending';

function getPendingDiv(container: HTMLElement): HTMLElement | null {
  return container.querySelector<HTMLElement>(`[data-testid="${PENDING_TESTID}"]`);
}

function getSageDiv(container: HTMLElement): HTMLElement | null {
  const track = container.querySelector<HTMLElement>('[role="progressbar"]');
  if (!track) return null;
  const children = Array.from(track.children) as HTMLElement[];
  return children.find((c) => c.getAttribute('data-testid') !== PENDING_TESTID) ?? null;
}

describe('ProgressBar', () => {
  it('renders only the sage segment when no pending prop is set', () => {
    const { container } = render(<ProgressBar value={3} total={10} />);

    const sage = getSageDiv(container);
    const pending = getPendingDiv(container);
    expect(sage).not.toBeNull();
    expect(pending).not.toBeNull();
    expect(sage!.style.width).toBe('30%');
    expect(pending!.style.width).toBe('0%');
    expect(pending!.style.left).toBe('30%');
  });

  it('renders both segments with correct widths when pending is set', () => {
    const { container } = render(<ProgressBar value={3} total={10} pending={2} />);

    const sage = getSageDiv(container);
    const pending = getPendingDiv(container);
    expect(sage!.style.width).toBe('30%');
    expect(pending!.style.width).toBe('20%');
    // Pending must start exactly where the sage ends so the two segments
    // butt up against each other (the sage paints on top to hide any
    // sub-pixel seam).
    expect(pending!.style.left).toBe('30%');
  });

  it('clamps pending so value + pending never exceeds total', () => {
    const { container } = render(<ProgressBar value={7} total={10} pending={99} />);

    const sage = getSageDiv(container);
    const pending = getPendingDiv(container);
    expect(sage!.style.width).toBe('70%');
    // 7 validated + 3 remaining = pending caps at 30 %, not 990 %.
    expect(pending!.style.width).toBe('30%');
    expect(pending!.style.left).toBe('70%');
  });

  it('clamps negative pending to 0', () => {
    const { container } = render(<ProgressBar value={4} total={10} pending={-5} />);

    const pending = getPendingDiv(container);
    expect(pending!.style.width).toBe('0%');
  });

  it('aria-valuenow reflects value only, never value + pending', () => {
    render(<ProgressBar value={3} total={10} pending={2} />);
    const bar = screen.getByRole('progressbar');
    expect(bar).toHaveAttribute('aria-valuenow', '3');
    expect(bar).toHaveAttribute('aria-valuemax', '10');
    expect(bar).toHaveAttribute('aria-valuemin', '0');
  });

  it('still renders both divs when total is zero (no NaN widths)', () => {
    const { container } = render(<ProgressBar value={0} total={0} pending={0} />);
    const sage = getSageDiv(container);
    const pending = getPendingDiv(container);
    expect(sage!.style.width).toBe('0%');
    expect(pending!.style.width).toBe('0%');
  });
});
