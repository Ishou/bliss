/**
 * FitText convergence guard.
 *
 * The flicker bug (mobile clue cells, post-PR-#196): font-size cycles
 * between two adjacent values forever because FitText's
 * `style.fontSize` write nudges the cell's container-query units, which
 * fires the cell's ResizeObserver, which re-runs `fit`, which picks a
 * subtly different "best", which writes again, and so on.
 *
 * The fix is a convergence guard: once the algorithm has settled at a
 * font size for given container dimensions, repeated ResizeObserver
 * fires with the same dimensions are no-ops — both the binary search
 * and the DOM write are skipped, so the loop has no fuel.
 *
 * jsdom does not auto-fire ResizeObserver. We replace the no-op polyfill
 * (vitest.setup.ts) with a per-test mock that captures the callback and
 * lets the test fire it on demand, and stub clientWidth/scrollWidth so
 * the binary search has a deterministic fixed point. The flicker is
 * reproduced as: 5 RO fires in a row → assert font-size never changes
 * between fires (i.e. the algorithm doesn't oscillate).
 */
import { render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FitText } from '@/ui/components/grid/FitText';

interface CapturedRO {
  fire: () => void;
  observed: Element | null;
}

const cleanups: Array<() => void> = [];
afterEach(() => {
  while (cleanups.length) cleanups.pop()?.();
  vi.unstubAllGlobals();
});

function captureResizeObserver(): CapturedRO {
  const captured: CapturedRO = { fire: () => {}, observed: null };
  class CapturingRO {
    private cb: ResizeObserverCallback;
    constructor(cb: ResizeObserverCallback) {
      this.cb = cb;
      captured.fire = () => this.cb([], this as unknown as ResizeObserver);
    }
    observe(el: Element) { captured.observed = el; }
    unobserve() {}
    disconnect() {}
  }
  vi.stubGlobal('ResizeObserver', CapturingRO);
  return captured;
}

function stubDimensions(span: HTMLElement, cw: number, ch: number, charPxRatio = 0.6, len = 6) {
  const readFont = () => {
    const px = span.style.fontSize.replace('px', '');
    return Number.parseInt(px, 10) || 0;
  };
  const widthForFont = (font: number) => Math.round(font * charPxRatio * len);
  const heightForFont = (font: number) => font;
  const overrides: Array<[string, PropertyDescriptor]> = [
    ['clientWidth', { configurable: true, get: () => cw }],
    ['clientHeight', { configurable: true, get: () => ch }],
    ['scrollWidth', { configurable: true, get: () => widthForFont(readFont()) }],
    ['scrollHeight', { configurable: true, get: () => heightForFont(readFont()) }],
  ];
  const originals = overrides.map(([prop]) => [
    prop,
    Object.getOwnPropertyDescriptor(span, prop),
  ] as const);
  for (const [prop, descriptor] of overrides) {
    Object.defineProperty(span, prop, descriptor);
  }
  cleanups.push(() => {
    for (const [prop, original] of originals) {
      if (original) Object.defineProperty(span, prop, original);
      else delete (span as unknown as Record<string, unknown>)[prop];
    }
  });
}

describe('FitText convergence guard', () => {
  it('does not oscillate the font-size on repeated ResizeObserver fires (mobile cells)', () => {
    // Reproduce a typical mobile def-cell sized boundary case.
    // cw=70 is around what a stacked half-cell measures on a 320 px wide
    // viewport in PR #196's flex shell layout. With ratio bounds 0.20–0.28
    // the comfortable range resolves to [14, 19] px. 6-char text with
    // charPxRatio=0.6 fits at font ≤ 70/3.6 ≈ 19.4 px, so the algorithm
    // converges to 19 px (the ceiling). A correct implementation must not
    // re-write the DOM after convergence: that's the whole point of the
    // guard, otherwise FitText's own writes feed the cell-resize loop.
    const ro = captureResizeObserver();
    const result = render(
      <FitText text="psycho" min={0.20} max={0.28} unit="ratio" />,
    );
    const span = result.container.querySelector('span') as HTMLSpanElement;
    stubDimensions(span, 70, 35, 0.6, 'psycho'.length);

    // Force the first fit() under the stubs by re-rendering — the initial
    // effect ran before stubs were in place (clientWidth was 0).
    result.rerender(<FitText text="psycho " min={0.20} max={0.28} unit="ratio" />);
    const settled = span.style.fontSize;
    expect(settled).toMatch(/px$/);

    // Simulate the cell's outer container queueing 5 ResizeObserver fires
    // in a row, e.g. because the cqi-driven cell box wobbles by 1 px as
    // the page settles. Without the convergence guard, the font-size
    // would cycle between two values across these fires; with the guard,
    // it stays pinned at the converged size.
    for (let i = 0; i < 5; i++) {
      ro.fire();
      expect(span.style.fontSize).toBe(settled);
    }
  });

  it('does not re-write DOM when the converged size repeats across same-dimension fires', () => {
    // Stronger contract: the guard must skip the DOM write entirely on
    // a no-op tick, otherwise the writes themselves perturb layout and
    // can re-fire the observer. We assert this by spying on the
    // setter — every call to `style.fontSize = …` is counted, and the
    // count must NOT grow after fits with same dimensions and same
    // converged size.
    const ro = captureResizeObserver();
    const result = render(
      <FitText text="déco" min={0.22} max={0.32} unit="ratio" />,
    );
    const span = result.container.querySelector('span') as HTMLSpanElement;
    stubDimensions(span, 80, 40, 0.6, 'déco'.length);
    result.rerender(<FitText text="déco " min={0.22} max={0.32} unit="ratio" />);

    // After the first fit, count subsequent writes from spying on the
    // CSSStyleDeclaration prototype's setProperty path. We instead
    // wrap `style.fontSize` with a tracking setter on this element only.
    let writeCount = 0;
    const realDescriptor = Object.getOwnPropertyDescriptor(span.style, 'fontSize')
      ?? Object.getOwnPropertyDescriptor(CSSStyleDeclaration.prototype, 'fontSize');
    let backing = span.style.fontSize;
    Object.defineProperty(span.style, 'fontSize', {
      configurable: true,
      get: () => backing,
      set: (v: string) => {
        backing = v;
        writeCount++;
      },
    });
    cleanups.push(() => {
      if (realDescriptor) Object.defineProperty(span.style, 'fontSize', realDescriptor);
    });

    // Fire ResizeObserver 5 times with the same stubbed dimensions.
    // Same-dimension fires are the canonical no-op case the guard covers.
    for (let i = 0; i < 5; i++) ro.fire();

    // Zero writes is the strict guarantee. The early-return at the top
    // of `fit()` (cw/ch unchanged) skips both the binary search and the
    // final write, so no font-size mutation hits the DOM after convergence.
    expect(writeCount).toBe(0);
  });
});
