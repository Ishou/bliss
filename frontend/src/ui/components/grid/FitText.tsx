import { useLayoutEffect, useRef } from 'react';

// Auto-fit text: bisect the largest fractional font size at which the
// text fits its container without overflow.
//
// `min` and `max` are interpreted per the `unit` prop:
//   * `unit="px"` — absolute pixel font sizes (legacy mode).
//   * `unit="ratio"` — fractions of the container's measured width,
//     computed at fit time. This makes the fit *zoom-invariant*: a clue
//     that fits at one cell size fits at every cell size, because both
//     font and cell scale linearly together. The zoom-invariance contract
//     is what `scripts/eval/clue_metrics.py` validates against offline at
//     the reference cell size.
//
// Recomputes whenever the container resizes (grid responds to viewport /
// pinch-zoom), the text changes, or web fonts finish loading after the
// initial paint. RO callbacks are rAF-coalesced — multiple resize fires
// within one frame trigger at most one fit.

// Safe initial pixel font size. The first render happens BEFORE the
// container has a measured width, so the binary search inside
// useLayoutEffect bails out on `clientWidth === 0` and the inline size
// stays whatever React committed last. A null/missing inline size makes
// the browser inherit body font (~16px) — way too big for our 40-50px
// cells and causes a flash of overflowing text before ResizeObserver
// fires. Starting at a known-small px (8) means the worst-case initial
// render is "tiny but contained", never "huge and bleeding".
const INITIAL_PX = 8;

// No absolute pixel floor — the prior `ABSOLUTE_MIN_PX = 11` broke
// zoom-invariance below ~55 px cells. Phase 2's fallback is now a
// RATIO multiple of `min` (`PHASE2_FLOOR_FACTOR`), so a clue that
// can't fit in [min, max] drops to a smaller ratio that still scales
// linearly with cell size. Per-clue zoom-invariance is preserved:
// the same clue renders at the same font/cell ratio at every screen
// size, just at a smaller ratio than the comfortable floor.
//
// Phase 2 exists for cases the offline gate didn't catch (e.g. stale
// runtime data, or clue text added after the gate ran): rather than
// committing the comfortable floor and visibly clipping the text, we
// shrink further — preserving the user-stated preference of
// "syllabic word-break / smaller-but-readable text > overflow".
// 0.5 is a generous range (e.g. min=0.18 → Phase 2 floor 0.09 — at a
// 100 px cell that's 9 px, near the legibility limit but with full
// content visible).
const PHASE2_FLOOR_FACTOR = 0.5;

// Bisection convergence target. The search stops when the candidate
// window shrinks below this. 0.25 px is below sub-pixel rendering
// granularity on every realistic DPR, so the residual error is
// invisible. Lower values just burn more iterations without changing
// what the user sees.
const FIT_EPSILON_PX = 0.25;

// Width-axis slop on the fits-test. Even with fractional content
// measurement (Range.getBoundingClientRect) and fractional container
// measurement (Element.getBoundingClientRect), there's residual noise
// at sub-half-pixel boundaries from glyph hinting and DPR rounding.
// 0.5 px absorbs that without permitting visible overflow — it's
// below the threshold of perception on any realistic display, and
// the cell's `overflow: hidden` eats this much invisibly.
const SLOP_PX = 0.5;

// Hard cap on bisection iterations. log2((hi-lo)/EPSILON) is ~7 for
// typical ranges, so 30 is comfortably above what the algorithm needs
// even in pathological cases. Catches a runaway loop from a
// measurement bug (e.g. a stub returning NaN) instead of hanging.
const MAX_ITERATIONS = 30;

export function FitText({
  text, min, max, className, title, unit = 'px',
}: {
  text: string;
  min: number;
  max: number;
  className?: string;
  title?: string;
  unit?: 'px' | 'ratio';
}) {
  const ref = useRef<HTMLSpanElement>(null);

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;

    // Convergence guard against measurement-then-resize feedback. The
    // ancestor flex shell uses `container-type: size`, so font-size
    // writes that change `el`'s rendered box could in principle
    // perturb cqw values upstream. Two layers of defence:
    //   * `lastCw / lastCh`: if dimensions haven't changed
    //     significantly since the last converged fit, skip the search
    //     AND the DOM write — no mutation means no perturbation.
    //   * Observing the PARENT instead of `el`: parent's box is
    //     determined by the cell, not by FitText's content, so font-
    //     size writes can't fire the observer in the first place.
    let lastBest = -1;
    let lastCw = -1;
    let lastCh = -1;
    let cancelled = false;

    // Available content box. Prefer the parent's BCR (fractional) so
    // that 1-px integer-rounding boundaries during continuous resize
    // don't produce visible font-size jumps. Falls back to the
    // element's integer clientWidth/Height when BCR is unavailable
    // (jsdom returns 0×0; existing tests stub clientWidth/Height on
    // the span itself to drive the algorithm).
    //
    // The span fills the parent's content box via `flex: 1;
    // alignSelf: stretch` (see Cell.tsx defText/defStackText), and
    // neither the parent nor the span have padding or border, so
    // BCR.width === content-box width. If that ever changes, this
    // function needs to subtract padding/border via getComputedStyle.
    const measureAvailable = (): { w: number; h: number } => {
      const parent = el.parentElement;
      if (parent) {
        const bcr = parent.getBoundingClientRect();
        if (bcr.width > 0 && bcr.height > 0) {
          return { w: bcr.width, h: bcr.height };
        }
      }
      return { w: el.clientWidth, h: el.clientHeight };
    };

    // Width: sub-pixel precision via Range.getBoundingClientRect().
    // jsdom does not compute layout; its Range stubs lack the method.
    // Fall back to scrollWidth so dimension-stubbed tests keep working.
    const measureContentWidth = (): number => {
      const range = document.createRange();
      range.selectNodeContents(el);
      const rect = typeof range.getBoundingClientRect === 'function'
        ? range.getBoundingClientRect()
        : null;
      if (!rect || rect.width === 0) return el.scrollWidth;
      return rect.width;
    };

    // Height: scrollHeight, NOT Range. Range height is browser-
    // inconsistent — Safari returns the glyph bbox, missing the
    // half-leading from line-height: 1.1, which under-reports
    // rendered height by ~10 % per line and lets the fits-test
    // approve sizes that overflow vertically. scrollHeight is
    // layout-engine-reported, includes the full line box, and rounds
    // up so it errs on the safe side.
    const fitsAt = (font: number, cw: number, ch: number): boolean => {
      el.style.fontSize = `${font}px`;
      const w = measureContentWidth();
      const h = el.scrollHeight;
      return w <= cw + SLOP_PX && h <= ch;
    };

    const fit = () => {
      if (cancelled) return;
      const { w: cw, h: ch } = measureAvailable();
      if (cw <= 0 || ch <= 0) return;
      if (
        Math.abs(cw - lastCw) < FIT_EPSILON_PX
        && Math.abs(ch - lastCh) < FIT_EPSILON_PX
        && lastBest > 0
      ) return;

      // Comfortable range, fractional. Prior versions floored to
      // integers; that produces 1-px font jumps when the cell width
      // crosses an integer boundary during a smooth resize, which the
      // user reads as flicker. Keeping the bounds fractional lets the
      // bisection produce smoothly varying fits.
      const lo0 = unit === 'ratio' ? Math.max(1, min * cw) : min;
      const hi0 = unit === 'ratio' ? Math.max(lo0, max * cw) : max;

      // Phase 1: bisect [lo0, hi0]. Probe the ceiling first — if hi0
      // already fits, the search is done.
      let lo = lo0;
      let hi = hi0;
      let best = -1;
      let iter = 0;
      if (fitsAt(hi0, cw, ch)) {
        best = hi0;
      } else {
        while (hi - lo > FIT_EPSILON_PX && iter++ < MAX_ITERATIONS) {
          const mid = (lo + hi) / 2;
          if (fitsAt(mid, cw, ch)) {
            best = mid;
            lo = mid;
          } else {
            hi = mid;
          }
        }
      }
      // Phase 2: if nothing in [lo0, hi0] fit, drop below the
      // comfortable floor down to `lo0 × PHASE2_FLOOR_FACTOR`. Same
      // fractional bisection. The floor is a RATIO multiple, not an
      // absolute pixel value — that's what keeps per-clue zoom-
      // invariance: the same clue renders at the same font/cell
      // ratio at every screen size, just smaller than `min`.
      //
      // Commits the floor regardless if even that doesn't fit; the
      // cell's overflow:hidden clips and the offline gate's CI surfaces
      // the bad data. We never commit a fontSize larger than what fits
      // (best stays at the largest verified-fitting size).
      if (best < 0) {
        const phase2Floor = lo0 * PHASE2_FLOOR_FACTOR;
        let lo2 = phase2Floor;
        let hi2 = lo0;
        if (fitsAt(lo2, cw, ch)) {
          best = lo2;
          iter = 0;
          while (hi2 - lo2 > FIT_EPSILON_PX && iter++ < MAX_ITERATIONS) {
            const mid = (lo2 + hi2) / 2;
            if (fitsAt(mid, cw, ch)) {
              best = mid;
              lo2 = mid;
            } else {
              hi2 = mid;
            }
          }
        } else {
          best = phase2Floor;
        }
      }

      lastCw = cw;
      lastCh = ch;
      lastBest = best;
      // Restore the inline font-size — fitsAt may have left it at an
      // intermediate bisection value. Direct DOM write (no setState)
      // because rendering 100+ cells through React per RO fire was
      // the bulk of the resize-cascade cost.
      el.style.fontSize = `${best}px`;
    };

    // Initial synchronous fit so the first paint shows the right size.
    fit();

    // Web fonts may swap in after first paint; their metrics differ
    // from the fallback's, which silently invalidates the first fit.
    // Re-fit when fonts settle, but only if they were actually loading
    // at mount time — warm reloads and jsdom skip the refit, which
    // keeps the convergence-guard tests deterministic.
    const fonts = document.fonts;
    if (fonts && fonts.status === 'loading') {
      fonts.ready.then(() => {
        if (cancelled) return;
        lastCw = -1;
        lastCh = -1;
        fit();
      });
    }

    // rAF-coalesce RO callbacks. During a continuous resize the
    // browser fires the observer aligned to frames, sometimes
    // multiple times per frame. Without coalescing, each fire runs
    // the full bisection and may pick a slightly different best; the
    // user reads the rapid-fire 1-px font shifts as flicker.
    // Coalescing means at most one fit per frame.
    let rafId: number | null = null;
    const scheduleFit = () => {
      if (rafId !== null) return;
      rafId = requestAnimationFrame(() => {
        rafId = null;
        fit();
      });
    };

    // Observe the parent. Our font-size writes change `el` but cannot
    // change the parent's box, so the observer can't fire on its own
    // writes — the lastCw/lastCh guard becomes belt-and-suspenders
    // for the parent's own sub-pixel noise.
    const ro = new ResizeObserver(scheduleFit);
    ro.observe(el.parentElement ?? el);
    return () => {
      cancelled = true;
      if (rafId !== null) cancelAnimationFrame(rafId);
      ro.disconnect();
    };
  }, [text, min, max, unit]);

  return (
    <span
      ref={ref}
      className={className}
      title={title}
      style={{ fontSize: `${INITIAL_PX}px` }}
    >
      {text}
    </span>
  );
}
