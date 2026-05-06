import { useLayoutEffect, useRef, useState } from 'react';

// Auto-fit text: binary-search the largest integer pixel font size at which
// the text fits its container without overflow.
//
// `min` and `max` are interpreted per the `unit` prop:
//   * `unit="px"` — absolute pixel font sizes (legacy mode).
//   * `unit="ratio"` — fractions of the container's clientWidth, computed at
//     fit time. This makes the fit *zoom-invariant*: a clue that fits at one
//     cell size fits at every cell size, because both font and cell scale
//     linearly together. The zoom-invariance contract is what
//     `scripts/eval/clue_metrics.py` validates against offline at the
//     reference cell size.
//
// Recomputes whenever the container resizes (grid responds to viewport /
// pinch-zoom) or the text changes.
// Safe initial pixel font size. The first render happens BEFORE the
// container has a measured width, so the binary search inside
// useLayoutEffect bails out on `clientWidth === 0` and the inline size
// stays whatever React committed last. A null/missing inline size makes
// the browser inherit body font (~16px) — way too big for our 40-50px
// cells and causes a flash of overflowing text before ResizeObserver
// fires. Starting at a known-small px (8) means the worst-case initial
// render is "tiny but contained", never "huge and bleeding".
const INITIAL_PX = 8;

// Absolute minimum font size in pixels. Raised post-PR-#195 from 6 → 11:
// at 6 px even high-DPR displays produce text that's literally
// unreadable on dense crossword cells (the user's screenshot showed
// "psycho" / "gagnée" sitting at ~6–8 px next to short clues like
// "déco" rendering at the comfortable ceiling — visually jarring and
// worse than honest clipping). 11 px is one notch below the typical
// readability floor for non-elderly eyes (~12 px); we prefer
// `overflow: hidden` clipping past this point because clipped text is
// still recognizable in shape, while sub-readable text is just noise.
const ABSOLUTE_MIN_PX = 11;

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
  const [size, setSize] = useState(INITIAL_PX);

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;

    // Convergence guard against a measure-then-resize feedback loop.
    //
    // The ancestor flex shell is `container-type: size` (see Grid.tsx
    // `gridShell`) and the grid wrapper is sized by `min(100cqw, 100cqh,
    // 720px)`. When FitText writes `el.style.fontSize = …`, the rendered
    // text height/width changes, which can nudge the cell's content box
    // (and therefore the cqi/cqw values it computes against) by a
    // sub-pixel amount, which fires the ResizeObserver, which re-runs
    // `fit`, which may pick a slightly different "best" because the new
    // clientWidth changed by 1 px, which writes a new font, ad infinitum.
    // On mobile cells (small enough that integer-px font choices straddle
    // the comfortable range) this oscillates between two adjacent sizes —
    // the visible "flicker".
    //
    // We break the loop with two pieces of state preserved across calls:
    //   * `lastBest`: the font size last applied to the DOM. If the new
    //     `best` equals it, the algorithm converged — skip the final
    //     style write AND the setState, so the ResizeObserver has no
    //     reason to fire again from FitText's own activity.
    //   * `lastDims`: the container dimensions that produced `lastBest`.
    //     If a ResizeObserver fire reports the same `cw`/`ch`, skip the
    //     binary search entirely. This also avoids the binary search's
    //     intermediate `style.fontSize` writes, which themselves perturb
    //     layout and could feed the loop on a slow re-converge.
    let lastBest = -1;
    let lastCw = -1;
    let lastCh = -1;

    const fit = () => {
      const cw = el.clientWidth;
      const ch = el.clientHeight;
      if (cw === 0 || ch === 0) return;
      if (cw === lastCw && ch === lastCh && lastBest !== -1) return;

      // Resolve ratios → integer px relative to the current container width.
      // `Math.max(1, …)` guards against degenerate sizes during the first
      // pre-layout render where clientWidth can briefly be 0 or 1.
      const lo0 = unit === 'ratio' ? Math.max(1, Math.floor(min * cw)) : min;
      const hi0 = unit === 'ratio' ? Math.max(lo0, Math.floor(max * cw)) : max;

      // Test whether `font` fits the container without scrolling.
      const fitsAt = (font: number): boolean => {
        el.style.fontSize = `${font}px`;
        return el.scrollWidth <= cw && el.scrollHeight <= ch;
      };

      // Phase 1: binary-search the comfortable range [lo0, hi0]. Best
      // result, if any size in the range fits.
      let lo = lo0;
      let hi = hi0;
      let best = -1;
      while (lo <= hi) {
        const mid = (lo + hi) >> 1;
        if (fitsAt(mid)) {
          best = mid;
          lo = mid + 1;
        } else {
          hi = mid - 1;
        }
      }

      // Phase 2: if nothing in the comfortable range fit, the clue is too
      // long for the current cell size — drop below lo0 to ABSOLUTE_MIN
      // (6 px, smallest readable on typical DPRs) and pick the largest
      // size that fits. This means the legibility floor (lo0) is a target,
      // not a hard minimum: we'd rather render small-but-complete than
      // clipped. With the offline `clue_metrics.fits_single_cell` gate
      // upstream, this fallback rarely fires on shipped data.
      if (best === -1) {
        let phase2Best = ABSOLUTE_MIN_PX;
        for (let f = lo0 - 1; f >= ABSOLUTE_MIN_PX; f--) {
          if (fitsAt(f)) {
            phase2Best = f;
            break;
          }
        }
        best = phase2Best;
      }

      lastCw = cw;
      lastCh = ch;
      // Restore the inline font-size — `fitsAt` may have left it at an
      // intermediate binary-search value. If the converged size equals
      // the last applied size, skip the setState: no state update means
      // no React re-render and no new commit-time mutation that could
      // feed the resize loop.
      el.style.fontSize = `${best}px`;
      if (best === lastBest) return;
      lastBest = best;
      setSize(best);
    };

    fit();
    const ro = new ResizeObserver(fit);
    ro.observe(el);
    return () => ro.disconnect();
  }, [text, min, max, unit]);

  return (
    <span
      ref={ref}
      className={className}
      title={title}
      style={{ fontSize: `${size}px` }}
    >
      {text}
    </span>
  );
}
