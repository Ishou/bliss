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
    // A 10-char text in a square cell, RATIO_MIN=0.18, RATIO_MAX=0.32.
    // Scroll width mock = font × 0.6 × 10 = 6×font, so it fits at
    // font ≤ cw/6. Capped at max(cw × 0.32). For cw=100, max font=32,
    // fit-cap from text = 16, so settled = 16.
    const r50 = settle('abcdefghij', 0.18, 0.32, 50, 100);
    const r100 = settle('abcdefghij', 0.18, 0.32, 100, 200);
    const r200 = settle('abcdefghij', 0.18, 0.32, 200, 400);

    // The ratio settled-font / cell-width should be (approximately) the
    // same across the three cell sizes — that's zoom invariance.
    const ratio = (font: number, cell: number) => font / cell;
    const tol = 0.04;
    expect(Math.abs(ratio(r50, 50) - ratio(r100, 100))).toBeLessThan(tol);
    expect(Math.abs(ratio(r100, 100) - ratio(r200, 200))).toBeLessThan(tol);
  });

  it('falls below the ratio floor rather than clipping when content forces it', () => {
    // A 30-char text with scroll mock = font × 0.6 × 30 = 18 × font.
    // At cw=100 the comfortable range is [18, 32] px, none of which fit
    // (all yield scrollWidth ≥ 18 × 18 = 324 > 100). The implementation
    // must drop below the floor (18) to whatever does fit, all the way
    // down to 6 px (ABSOLUTE_MIN_PX) if needed. Better small-but-readable
    // than clipped-and-invisible.
    const cw = 100;
    const settled = settle('M'.repeat(30), 0.18, 0.32, cw, 100);
    // At cw=100 with the mock, font ≤ 100 / (0.6 × 30) = 5.55, so the
    // largest fitting integer is 5. Phase 2 floors at 6 — so we never
    // drop below 6, even if 6 also doesn't fit (the "tiny but contained"
    // safety, since CSS overflow:hidden on the cell wrappers handles the
    // hard contain).
    expect(settled).toBeLessThanOrEqual(Math.floor(0.18 * cw));
    expect(settled).toBeGreaterThanOrEqual(6);
  });
});
