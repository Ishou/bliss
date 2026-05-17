import { useRef, useImperativeHandle, type ComponentProps } from 'react';
import { render, screen, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
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
  beforeEach(() => {
    _simScale = 1;
    _fireZoom = null;
  });

  it('does not render scrollbars or minimap at scale 1', () => {
    render(<Grid puzzle={SAMPLE_PUZZLE} />);
    expect(screen.queryByRole('scrollbar')).toBeNull();
    expect(screen.queryByRole('img', { name: /aperçu/i })).toBeNull();
  });

  it('the inner <div role="grid"> has id="puzzle-grid" so aria-controls resolves', () => {
    render(<Grid puzzle={SAMPLE_PUZZLE} />);
    expect(screen.getByRole('grid')).toHaveAttribute('id', 'puzzle-grid');
  });

  it('minimap renders inside grid-area, not inside grid-stage, when zoomed', async () => {
    // jsdom reports 0 for offsetWidth/Height; override so the gridFramePx
    // effect sets non-zero dimensions and the minimap's render guard passes.
    const origW = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth');
    const origH = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
    Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { get: () => 720, configurable: true });
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { get: () => 540, configurable: true });

    // Override the global no-op ResizeObserver stub (vitest.setup.ts) so the
    // gridFramePx effect actually fires the update callback.
    const OrigRO = globalThis.ResizeObserver;
    globalThis.ResizeObserver = class {
      #cb: ResizeObserverCallback;
      constructor(cb: ResizeObserverCallback) { this.#cb = cb; }
      observe() { this.#cb([], this as unknown as ResizeObserver); }
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver;

    // Make rAF synchronous so the rAF callback inside handleTransform runs
    // immediately and sets transformState.scale = _simScale (1.5) within
    // the same act() call, satisfying GridMinimap's `scale > 1.01` guard.
    const origRAF = window.requestAnimationFrame;
    window.requestAnimationFrame = (fn: FrameRequestCallback) => { fn(0); return 0; };

    render(<Grid puzzle={SAMPLE_PUZZLE} />);

    // Drive isZoomedIn → true and transformState.scale → 1.5 via the mock.
    await act(async () => { _fireZoom?.(1.5); });

    const minimap = await screen.findByRole('img', { name: /aperçu/i });

    // Structural invariant: the minimap SVG is a sibling of grid-shell inside
    // grid-area, NOT a descendant of grid-stage (which wraps overlayFrame).
    const gridArea = screen.getByTestId('grid-area');
    const gridStage = screen.getByTestId('grid-stage');
    expect(gridArea).toContainElement(minimap as HTMLElement);
    expect(gridStage).not.toContainElement(minimap as HTMLElement);

    // Restore
    window.requestAnimationFrame = origRAF;
    if (origW) Object.defineProperty(HTMLElement.prototype, 'offsetWidth', origW);
    if (origH) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', origH);
    globalThis.ResizeObserver = OrigRO;
  });
});
