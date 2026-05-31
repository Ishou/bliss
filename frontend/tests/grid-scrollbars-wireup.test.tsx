import { useRef, useImperativeHandle, type ComponentProps } from 'react';
import { render, screen, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { SAMPLE_PUZZLE } from '@/domain';
import { Grid } from '@/ui/components/grid';

// Simulated scale shared between the mock ref and fireZoom.
let _simScale = 1;
// Set by the mock component on each render so tests can trigger onTransform.
let _fireZoom: ((scale: number) => void) | null = null;

// Intercept TransformWrapper so tests can:
//   1. Override what `transformWrapperRef.current.state.scale` returns
//      (so GridMinimap's `if (scale <= 1.01) return null` guard can be cleared).
//   2. Directly call Grid's `handleTransform` via `_fireZoom` without relying on
//      rAF-driven animation (which does not advance in jsdom without fake timers).
// The actual TransformWrapper is still mounted for all other behaviour.
vi.mock('react-zoom-pan-pinch', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-zoom-pan-pinch')>();
  const { TransformWrapper: ActualTW } = actual;
  type WrapperProps = ComponentProps<typeof ActualTW>;

  // React 19: ref is a plain prop; no forwardRef needed.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  function MockTW({ onTransform, children, ref: externalRef, ...rest }: WrapperProps & { ref?: any }) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const innerRef = useRef<any>(null);

    // Override state.scale via the ref Grid holds, so the rAF callback inside
    // handleTransform reads _simScale instead of the real (still-at-1) state.
    useImperativeHandle(externalRef, () => ({
      get state() { return { scale: _simScale, positionX: 0, positionY: 0 }; },
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      zoomIn:        (...a: unknown[]) => (innerRef.current as any)?.zoomIn(...a),
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      zoomOut:       (...a: unknown[]) => (innerRef.current as any)?.zoomOut(...a),
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      centerView:    (...a: unknown[]) => (innerRef.current as any)?.centerView(...a),
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      zoomToElement: (...a: unknown[]) => (innerRef.current as any)?.zoomToElement(...a),
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      setTransform:  (...a: unknown[]) => (innerRef.current as any)?.setTransform(...a),
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      resetTransform:(...a: unknown[]) => (innerRef.current as any)?.resetTransform(...a),
    }), []);

    _fireZoom = (scale: number) => {
      _simScale = scale;
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      onTransform?.({ state: { scale, positionX: 0, positionY: 0 } } as any, { scale, positionX: 0, positionY: 0 } as any);
    };

    return <ActualTW ref={innerRef} onTransform={onTransform} {...rest}>{children}</ActualTW>;
  }
  return { ...actual, TransformWrapper: MockTW };
});

describe('Grid scrollbars + minimap wireup', () => {
  // jsdom reports 0 for offsetWidth/Height; override so the gridFramePx
  // effect sets non-zero dimensions and the minimap's render guard passes.
  // The ResizeObserver override fires the observe callback synchronously
  // (vitest.setup.ts ships a no-op), and a microtask-deferred rAF (see
  // beforeEach) lets handleTransform's coalesced state update land within act().
  let origW: PropertyDescriptor | undefined;
  let origH: PropertyDescriptor | undefined;
  let origRO: typeof ResizeObserver;
  let origRAF: typeof window.requestAnimationFrame;

  beforeEach(() => {
    _simScale = 1;
    _fireZoom = null;
    origW = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth');
    origH = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
    Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { get: () => 720, configurable: true });
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { get: () => 540, configurable: true });
    origRO = globalThis.ResizeObserver;
    globalThis.ResizeObserver = class {
      #cb: ResizeObserverCallback;
      constructor(cb: ResizeObserverCallback) { this.#cb = cb; }
      observe() { this.#cb([], this as unknown as ResizeObserver); }
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver;
    origRAF = window.requestAnimationFrame;
    // Defer to a microtask (not a plain `fn(0)`) so the caller's
    // `rafRef.current = requestAnimationFrame(...)` assignment runs BEFORE the
    // callback nulls the ref — matching real async rAF ordering. A synchronous
    // `fn(0)` inverts that order and wedges handleTransform's coalescing guard
    // at a truthy id, so later transforms never flush. `await act(async …)`
    // drains the microtask, so the coalesced state update still lands in-test.
    window.requestAnimationFrame = (fn: FrameRequestCallback) => { queueMicrotask(() => fn(0)); return 0; };
  });

  afterEach(() => {
    window.requestAnimationFrame = origRAF;
    if (origW) Object.defineProperty(HTMLElement.prototype, 'offsetWidth', origW);
    if (origH) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', origH);
    globalThis.ResizeObserver = origRO;
  });

  it('renders the desktop minimap but no scrollbars at scale 1', () => {
    render(<Grid puzzle={SAMPLE_PUZZLE} />);
    // Minimap is no longer zoom-gated — it shows as soon as dims resolve.
    expect(screen.getByRole('img', { name: /aperçu/i })).toBeInTheDocument();
    // Scrollbars stay zoom-gated.
    expect(screen.queryByRole('scrollbar')).toBeNull();
  });

  it('the inner <div role="grid"> has id="puzzle-grid" so aria-controls resolves', () => {
    render(<Grid puzzle={SAMPLE_PUZZLE} />);
    expect(screen.getByRole('grid')).toHaveAttribute('id', 'puzzle-grid');
  });

  it('renders scrollbars once zoomed in', async () => {
    render(<Grid puzzle={SAMPLE_PUZZLE} />);
    await act(async () => { _fireZoom?.(1.5); });
    expect(screen.getAllByRole('scrollbar').length).toBeGreaterThan(0);
  });

  it('renders the minimap in the bottom controls bar, outside grid-area and grid-stage', () => {
    render(<Grid puzzle={SAMPLE_PUZZLE} />);
    const minimap = screen.getByRole('img', { name: /aperçu/i });
    // The minimap now lives in the desktop controls bar, a sibling AFTER
    // grid-area — no longer nested inside the grid's flex column.
    expect(screen.getByTestId('grid-area')).not.toContainElement(minimap as HTMLElement);
    expect(screen.getByTestId('grid-stage')).not.toContainElement(minimap as HTMLElement);
  });
});
