import { useLayoutEffect, useRef, useState } from 'react';

// Auto-fit text: binary-search the largest integer pixel font size at which
// the text fits its container without overflow (neither horizontal nor
// vertical). Recomputes whenever the container resizes (grid responds to
// viewport / pinch-zoom) or the text changes.
//
// The container is the rendered <span> itself: its size is dictated by the
// parent's flex layout (flex:1 in defText / defStackText), and we measure
// scrollWidth/scrollHeight against clientWidth/clientHeight at each candidate
// size. Setting `style.fontSize` directly during measurement avoids extra
// React renders inside the binary search; the final value is committed via
// setState so the next render owns it.
export function FitText({
  text, min, max, className, title,
}: {
  text: string;
  min: number;
  max: number;
  className?: string;
  title?: string;
}) {
  const ref = useRef<HTMLSpanElement>(null);
  const [size, setSize] = useState(max);

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;

    const fit = () => {
      const cw = el.clientWidth;
      const ch = el.clientHeight;
      if (cw === 0 || ch === 0) return;

      let lo = min;
      let hi = max;
      let best = min;
      while (lo <= hi) {
        const mid = (lo + hi) >> 1;
        el.style.fontSize = `${mid}px`;
        const fits = el.scrollWidth <= cw && el.scrollHeight <= ch;
        if (fits) {
          best = mid;
          lo = mid + 1;
        } else {
          hi = mid - 1;
        }
      }
      el.style.fontSize = `${best}px`;
      setSize(best);
    };

    fit();
    const ro = new ResizeObserver(fit);
    ro.observe(el);
    return () => ro.disconnect();
  }, [text, min, max]);

  return (
    <span ref={ref} className={className} title={title} style={{ fontSize: `${size}px` }}>
      {text}
    </span>
  );
}
