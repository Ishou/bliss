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

// Absolute minimum font size in pixels. Below this the text is unreadable
// even on high-DPR displays — but still preferable to clipping. The
// fit-below-floor fallback (Phase 2 below) targets this as its hard floor.
const ABSOLUTE_MIN_PX = 6;

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

    const fit = () => {
      const cw = el.clientWidth;
      const ch = el.clientHeight;
      if (cw === 0 || ch === 0) return;

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

      el.style.fontSize = `${best}px`;
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
