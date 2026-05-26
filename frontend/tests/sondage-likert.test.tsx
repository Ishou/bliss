import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import type { LikertScore } from '@/application/survey';
import { Likert } from '@/ui/components/sondage';

function Harness({ onChange }: { onChange?: (value: LikertScore) => void }) {
  const [value, setValue] = useState<LikertScore | null>(null);
  return (
    <Likert
      label="Quality"
      ariaLabel="Qualité"
      value={value}
      onChange={(v) => {
        setValue(v);
        onChange?.(v);
      }}
      leftHint="Mauvaise"
      rightHint="Excellente"
    />
  );
}

describe('Likert', () => {
  it('renders 5 radios labelled 1..5 inside a radiogroup', () => {
    render(<Harness />);
    const group = screen.getByRole('radiogroup', { name: 'Qualité' });
    expect(group).toBeInTheDocument();
    for (const score of [1, 2, 3, 4, 5]) {
      expect(screen.getByRole('radio', { name: String(score) })).toBeInTheDocument();
    }
  });

  it('starts with no radio checked and the first one tabbable', () => {
    render(<Harness />);
    const radios = screen.getAllByRole('radio');
    expect(radios).toHaveLength(5);
    for (const radio of radios) {
      expect(radio.getAttribute('aria-checked')).toBe('false');
    }
    expect(radios[0].getAttribute('tabindex')).toBe('0');
    for (const radio of radios.slice(1)) {
      expect(radio.getAttribute('tabindex')).toBe('-1');
    }
  });

  it('selects on click and shifts the tabbable index to the selected radio', () => {
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);
    const radio4 = screen.getByRole('radio', { name: '4' });
    act(() => { fireEvent.click(radio4); });
    expect(onChange).toHaveBeenCalledWith(4);
    expect(radio4.getAttribute('aria-checked')).toBe('true');
    expect(radio4.getAttribute('tabindex')).toBe('0');
    expect(screen.getByRole('radio', { name: '1' }).getAttribute('tabindex')).toBe('-1');
  });

  it('arrow-right moves selection forward', () => {
    render(<Harness />);
    const radio1 = screen.getByRole('radio', { name: '1' });
    act(() => {
      radio1.focus();
      fireEvent.keyDown(radio1, { key: 'ArrowRight' });
    });
    expect(screen.getByRole('radio', { name: '2' }).getAttribute('aria-checked')).toBe('true');
  });

  it('arrow-left wraps from 1 to 5', () => {
    render(<Harness />);
    const radio1 = screen.getByRole('radio', { name: '1' });
    act(() => {
      radio1.focus();
      fireEvent.keyDown(radio1, { key: 'ArrowLeft' });
    });
    expect(screen.getByRole('radio', { name: '5' }).getAttribute('aria-checked')).toBe('true');
  });

  it('Space selects the focused radio', () => {
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);
    const radio3 = screen.getByRole('radio', { name: '3' });
    act(() => {
      radio3.focus();
      fireEvent.keyDown(radio3, { key: ' ' });
    });
    expect(onChange).toHaveBeenCalledWith(3);
  });

  it('Home / End jump to the first / last radio', () => {
    render(<Harness />);
    const radio1 = screen.getByRole('radio', { name: '1' });
    act(() => {
      radio1.focus();
      fireEvent.keyDown(radio1, { key: 'End' });
    });
    expect(screen.getByRole('radio', { name: '5' }).getAttribute('aria-checked')).toBe('true');
    const radio5 = screen.getByRole('radio', { name: '5' });
    act(() => { fireEvent.keyDown(radio5, { key: 'Home' }); });
    expect(screen.getByRole('radio', { name: '1' }).getAttribute('aria-checked')).toBe('true');
  });
});
