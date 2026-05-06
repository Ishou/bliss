/**
 * Zoom-invariance contract: FitText with `unit="ratio"` produces a font
 * size that scales linearly with the cell's clientWidth, so a clue that
 * fits at one cell size fits at every cell size — only the absolute pixel
 * font changes. This is the test that locks the contract.
 *
 * jsdom does not compute layout, so we stub `clientWidth`, `clientHeight`,
 * `scrollWidth`, `scrollHeight` on the FitText span after mount. The mock
 * for scrollWidth/Height is `fontSize × len`, so the binary search has a
 * deterministic fit point at `font = floor(clientWidth / len)` — which
 * means the resolved font / clientWidth ratio depends only on the text
 * (not on cell size). That is the invariance we assert.
 */
import { render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { FitText } from '@/ui/components/grid/FitText';

interface DimensionState {
  clientWidth: number;
  clientHeight: number;
  scrollWidthForFont: (font: number) => number;
  scrollHeightForFont: (font: number) => number;
}

const cleanups: Array<() => void> = [];
afterEach(() => {
  while (cleanups.length) cleanups.pop()?.();
});

function installStubs(span: HTMLElement, dims: DimensionState) {
  // Track the current font-size so scrollWidth/Height can derive from it.
  // FitText writes font-size via inline `style.fontSize`, so we read that.
  const readFont = () => {
    const px = span.style.fontSize.replace('px', '');
    return Number.parseInt(px, 10) || 0;
  };
  const overrides: Array<[string, PropertyDescriptor | undefined]> = [
    ['clientWidth', { configurable: true, get: () => dims.clientWidth }],
    ['clientHeight', { configurable: true, get: () => dims.clientHeight }],
    ['scrollWidth', { configurable: true, get: () => dims.scrollWidthForFont(readFont()) }],
    ['scrollHeight', { configurable: true, get: () => dims.scrollHeightForFont(readFont()) }],
  ];
  const originals = overrides.map(([prop]) => [
    prop,
    Object.getOwnPropertyDescriptor(span, prop),
  ] as const);
  for (const [prop, descriptor] of overrides) {
    Object.defineProperty(span, prop, descriptor as PropertyDescriptor);
  }
  cleanups.push(() => {
    for (const [prop, original] of originals) {
      if (original) Object.defineProperty(span, prop, original);
      else delete (span as unknown as Record<string, unknown>)[prop];
    }
  });
}

describe('FitText ratio mode is zoom-invariant', () => {
  function settle(text: string, ratioMin: number, ratioMax: number, cw: number, ch: number): number {
    // text ≈ N narrow chars; scrollWidth = font × 0.6 × N (fits at font = cw / (0.6N))
    const len = text.length;
    const widthForFont = (font: number) => Math.round(font * 0.6 * len);
    const heightForFont = (font: number) => font; // 1 line, ≈ font height
    const result = render(
      <FitText
        text={text}
        min={ratioMin}
        max={ratioMax}
        unit="ratio"
        // remount each call — outer keyed by `cw` ensures fresh effect
        key={`cw-${cw}`}
      />,
    );
    const span = result.container.querySelector('span') as HTMLSpanElement;
    installStubs(span, {
      clientWidth: cw,
      clientHeight: ch,
      scrollWidthForFont: widthForFont,
      scrollHeightForFont: heightForFont,
    });
    // Force the effect to run *after* stubs are in place by mutating the
    // ResizeObserver disconnect target — easiest: call the callback that
    // ResizeObserver was given. vitest.setup polyfills ResizeObserver as a
    // no-op; we rely on a manual re-mount instead.
    result.rerender(
      <FitText
        text={`${text} `}
        min={ratioMin}
        max={ratioMax}
        unit="ratio"
        key={`cw-${cw}`}
      />,
    );
    return Number.parseInt(span.style.fontSize.replace('px', ''), 10) || 0;
  }

  it('produces font-to-cell ratios within rounding tolerance across cell sizes', () => {
    // 4-char text in a square cell, RATIO_MIN=0.22, RATIO_MAX=0.32.
    // Scroll width mock = font × 0.6 × 4 = 2.4×font, fits at
    // font ≤ cw/2.4. Picked to keep the comfortable range
    // [0.22cw, 0.32cw] entirely fitting at every cw under test, so
    // FitText settles at hi0 (= floor(0.32 × cw)) and the ratio is
    // identical across cell sizes (rounding aside).
    const r50 = settle('abcd', 0.22, 0.32, 50, 100);
    const r100 = settle('abcd', 0.22, 0.32, 100, 200);
    const r200 = settle('abcd', 0.22, 0.32, 200, 400);

    // The ratio settled-font / cell-width should be (approximately) the
    // same across the three cell sizes — that's zoom invariance.
    const ratio = (font: number, cell: number) => font / cell;
    const tol = 0.04;
    expect(Math.abs(ratio(r50, 50) - ratio(r100, 100))).toBeLessThan(tol);
    expect(Math.abs(ratio(r100, 100) - ratio(r200, 200))).toBeLessThan(tol);
  });

  it('clamps long-clue rendered font-size at the 11 px ABSOLUTE_MIN floor', () => {
    // A 30-char text with scroll mock = font × 0.6 × 30 = 18 × font.
    // At cw=100 the comfortable range is [22, 32] px, none of which
    // fit (all yield scrollWidth ≥ 22 × 18 = 396 > 100). The
    // implementation drops below the floor (22) but DOES NOT go below
    // `ABSOLUTE_MIN_PX = 11` — the cell's `overflow: hidden` clips
    // past 11 px. Sub-readable text below the ~12 px readability floor
    // is worse UX than honest clipping (see `FitText.tsx` for the
    // rationale + the post-PR-#195 fix that raised the floor from 6).
    const cw = 100;
    const settled = settle('M'.repeat(30), 0.22, 0.32, cw, 100);
    expect(settled).toBeLessThanOrEqual(Math.floor(0.22 * cw));
    expect(settled).toBeGreaterThanOrEqual(11);
  });
});
