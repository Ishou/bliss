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

  it('calls onPress on click', () => {
    const onPress = vi.fn();
    const { getByRole } = render(
      <KeyboardKey label="A" ariaLabel="Lettre A" onPress={onPress} />,
    );
    fireEvent.click(getByRole('button'));
    expect(onPress).toHaveBeenCalledTimes(1);
  });

  it('calls preventDefault on mousedown to preserve focus elsewhere', () => {
    const { getByRole } = render(
      <KeyboardKey label="A" ariaLabel="Lettre A" onPress={() => undefined} />,
    );
    const ev = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
    getByRole('button').dispatchEvent(ev);
    expect(ev.defaultPrevented).toBe(true);
  });

  it('disabled=true blocks onPress and sets aria-disabled', () => {
    const onPress = vi.fn();
    const { getByRole } = render(
      <KeyboardKey label="A" ariaLabel="Lettre A" disabled onPress={onPress} />,
    );
    fireEvent.click(getByRole('button'));
    expect(onPress).not.toHaveBeenCalled();
    expect(getByRole('button').getAttribute('aria-disabled')).toBe('true');
  });
});
