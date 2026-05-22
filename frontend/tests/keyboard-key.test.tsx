import { render, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { KeyboardKey } from '@/ui/components/keyboard/KeyboardKey';

describe('KeyboardKey', () => {
  it('renders a button with the given label and aria-label', () => {
    const { getByRole } = render(
      <KeyboardKey label="A" ariaLabel="Lettre A" onPress={() => undefined} />,
    );
    const btn = getByRole('button', { name: 'Lettre A' });
    expect(btn.textContent).toBe('A');
    expect(btn.getAttribute('type')).toBe('button');
  });

  it('calls onPress on Enter keydown (keyboard activation)', () => {
    const onPress = vi.fn();
    const { getByRole } = render(
      <KeyboardKey label="A" ariaLabel="Lettre A" onPress={onPress} />,
    );
    fireEvent.keyDown(getByRole('button'), { key: 'Enter' });
    expect(onPress).toHaveBeenCalledTimes(1);
  });

  it('calls onPress on Space keydown and prevents default (no page scroll)', () => {
    const onPress = vi.fn();
    const { getByRole } = render(
      <KeyboardKey label="A" ariaLabel="Lettre A" onPress={onPress} />,
    );
    const ev = fireEvent.keyDown(getByRole('button'), { key: ' ' });
    expect(onPress).toHaveBeenCalledTimes(1);
    // fireEvent returns whether the default was NOT prevented; we want it prevented.
    expect(ev).toBe(false);
  });

  it('ignores non-activation keys (Tab / letter)', () => {
    const onPress = vi.fn();
    const { getByRole } = render(
      <KeyboardKey label="A" ariaLabel="Lettre A" onPress={onPress} />,
    );
    fireEvent.keyDown(getByRole('button'), { key: 'Tab' });
    fireEvent.keyDown(getByRole('button'), { key: 'a' });
    expect(onPress).not.toHaveBeenCalled();
  });

  it('calls onPress on pointerdown (touch primary path) — fires once', () => {
    const onPress = vi.fn();
    const { getByRole } = render(
      <KeyboardKey label="A" ariaLabel="Lettre A" onPress={onPress} />,
    );
    const btn = getByRole('button');
    const ev = new PointerEvent('pointerdown', { bubbles: true, cancelable: true, button: 0 });
    btn.dispatchEvent(ev);
    expect(onPress).toHaveBeenCalledTimes(1);
    expect(ev.defaultPrevented).toBe(true);
  });

  it('does not double-fire when pointerdown is followed by a click in the same gesture', () => {
    const onPress = vi.fn();
    const { getByRole } = render(
      <KeyboardKey label="A" ariaLabel="Lettre A" onPress={onPress} />,
    );
    const btn = getByRole('button');
    btn.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true, button: 0 }));
    fireEvent.click(btn);
    expect(onPress).toHaveBeenCalledTimes(1);
  });

  it('does not double-fire when click arrives in a later macrotask after pointerdown (real-device sequence)', async () => {
    // Real-device repro: pointerdown handler runs, then the browser dispatches the
    // synthesized click as a SEPARATE macrotask after touchend. Any microtask-based
    // dedupe (e.g. queueMicrotask reset of a consumedRef) has already cleared by then.
    // Fix: there is no onClick handler — the synthesized click is delivered into nothing.
    const onPress = vi.fn();
    const { getByRole } = render(
      <KeyboardKey label="A" ariaLabel="Lettre A" onPress={onPress} />,
    );
    const btn = getByRole('button');
    btn.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true, button: 0 }));
    // Drain microtasks + yield a macrotask, simulating the gap between touchend and click.
    await Promise.resolve();
    await new Promise<void>((r) => setTimeout(r, 0));
    fireEvent.click(btn);
    expect(onPress).toHaveBeenCalledTimes(1);
  });

  it('ignores non-primary pointer buttons (right-click)', () => {
    const onPress = vi.fn();
    const { getByRole } = render(
      <KeyboardKey label="A" ariaLabel="Lettre A" onPress={onPress} />,
    );
    const btn = getByRole('button');
    const ev = new PointerEvent('pointerdown', { bubbles: true, cancelable: true, button: 2 });
    btn.dispatchEvent(ev);
    expect(onPress).not.toHaveBeenCalled();
    expect(ev.defaultPrevented).toBe(false);
  });

  it('suppresses the long-press context menu (preventDefault on contextmenu)', () => {
    const { getByRole } = render(
      <KeyboardKey label="A" ariaLabel="Lettre A" onPress={() => undefined} />,
    );
    const ev = new MouseEvent('contextmenu', { bubbles: true, cancelable: true });
    getByRole('button').dispatchEvent(ev);
    expect(ev.defaultPrevented).toBe(true);
  });

  it('disabled=true blocks onPress on pointerdown and keydown and sets aria-disabled', () => {
    const onPress = vi.fn();
    const { getByRole } = render(
      <KeyboardKey label="A" ariaLabel="Lettre A" disabled onPress={onPress} />,
    );
    const btn = getByRole('button');
    btn.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true, button: 0 }));
    fireEvent.keyDown(btn, { key: 'Enter' });
    fireEvent.keyDown(btn, { key: ' ' });
    expect(onPress).not.toHaveBeenCalled();
    expect(btn.getAttribute('aria-disabled')).toBe('true');
  });
});
