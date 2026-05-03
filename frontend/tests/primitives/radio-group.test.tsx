import { act, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { RadioGroup } from '@/ui/components/primitives';

// Smoke tests for the project-local `RadioGroup` primitive (ADR-0002 §2).
// Behavioural surface: renders a labelled radiogroup with one radio per
// option, marks the option matching `value` as checked, and fires
// `onValueChange` with the new value when the user picks a different
// option.
const options = [
  { value: 'small', label: 'Petit' },
  { value: 'medium', label: 'Moyen' },
  { value: 'large', label: 'Grand' },
] as const;

type Size = (typeof options)[number]['value'];

describe('RadioGroup', () => {
  it('renders a radiogroup with the label as accessible name', () => {
    render(
      <RadioGroup<Size>
        label="Taille"
        value="small"
        onValueChange={() => {}}
        options={options}
      />,
    );
    expect(screen.getByRole('radiogroup', { name: 'Taille' })).toBeInTheDocument();
  });

  it('renders one radio per option, with the option label as accessible name', () => {
    render(
      <RadioGroup<Size>
        label="Taille"
        value="small"
        onValueChange={() => {}}
        options={options}
      />,
    );
    expect(screen.getByRole('radio', { name: 'Petit' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Moyen' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Grand' })).toBeInTheDocument();
  });

  it('marks the option matching `value` as checked', () => {
    render(
      <RadioGroup<Size>
        label="Taille"
        value="medium"
        onValueChange={() => {}}
        options={options}
      />,
    );
    expect((screen.getByRole('radio', { name: 'Moyen' }) as HTMLInputElement).checked).toBe(true);
    expect((screen.getByRole('radio', { name: 'Petit' }) as HTMLInputElement).checked).toBe(false);
  });

  it('fires onValueChange with the new value when a different option is picked', async () => {
    const onValueChange = vi.fn();
    render(
      <RadioGroup<Size>
        label="Taille"
        value="small"
        onValueChange={onValueChange}
        options={options}
      />,
    );
    // Use the native `HTMLInputElement.click()` so jsdom toggles
    // `checked` before dispatching the click event — Ark's hidden-input
    // `onClick` reads `event.currentTarget.checked` to commit.
    const radio = screen.getByRole('radio', { name: 'Grand' }) as HTMLInputElement;
    await act(async () => { radio.click(); });
    expect(onValueChange).toHaveBeenCalledWith('large');
  });
});
