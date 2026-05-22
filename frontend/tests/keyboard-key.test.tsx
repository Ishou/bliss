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

  it('calls onPress on click (keyboard-driven activation)', () => {
    const onPress = vi.fn();
    const { getByRole } = render(
      <KeyboardKey label="A" ariaLabel="Lettre A" onPress={onPress} />,
    );
    fireEvent.click(getByRole('button'));
    expect(onPress).toHaveBeenCalledTimes(1);
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

  it('disabled=true blocks onPress on pointerdown and click and sets aria-disabled', () => {
    const onPress = vi.fn();
    const { getByRole } = render(
      <KeyboardKey label="A" ariaLabel="Lettre A" disabled onPress={onPress} />,
    );
    const btn = getByRole('button');
    btn.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true, button: 0 }));
    fireEvent.click(btn);
    expect(onPress).not.toHaveBeenCalled();
    expect(btn.getAttribute('aria-disabled')).toBe('true');
  });

  it('after pointerdown gesture completes, a later keyboard-triggered click still fires', async () => {
    const onPress = vi.fn();
    const { getByRole } = render(
      <KeyboardKey label="A" ariaLabel="Lettre A" onPress={onPress} />,
    );
    const btn = getByRole('button');
    // First gesture: pointerdown + synthesized click. Should fire once.
    btn.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, cancelable: true, button: 0 }));
    fireEvent.click(btn);
    expect(onPress).toHaveBeenCalledTimes(1);
    // Yield a microtask so the consumed ref resets.
    await Promise.resolve();
    // Second gesture: pure keyboard activation (Enter/Space on focused button → click).
    fireEvent.click(btn);
    expect(onPress).toHaveBeenCalledTimes(2);
  });
});
