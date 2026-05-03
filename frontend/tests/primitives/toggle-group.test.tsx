import { act, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ToggleGroup } from '@/ui/components/primitives';

// Smoke tests for the project-local `ToggleGroup` primitive (ADR-0002 §2).
// Behavioural surface: renders a labelled group with one toggle button
// per option, marks the option matching `value` as pressed, and fires
// `onValueChange` with the new value when the user picks a different
// option.
//
// In single-select mode (which the primitive hard-codes by passing
// `multiple={false}` to Ark), the underlying zag state machine still
// emits `role="radiogroup"` on the root and `role="radio"` on each
// item — the visual difference vs `RadioGroup` is that items are real
// `<button>` chips with a pressed/unpressed state instead of hidden
// `<input type="radio">` elements.
const options = [
  { value: 'small', label: 'Petit' },
  { value: 'medium', label: 'Moyen' },
  { value: 'large', label: 'Grand' },
] as const;

type Size = (typeof options)[number]['value'];

describe('ToggleGroup', () => {
  it('renders a group with the label as accessible name', () => {
    render(
      <ToggleGroup<Size>
        label="Taille"
        value="small"
        onValueChange={() => {}}
        options={options}
      />,
    );
    expect(screen.getByRole('radiogroup', { name: 'Taille' })).toBeInTheDocument();
  });

  it('renders one toggle per option, with the option label as accessible name', () => {
    render(
      <ToggleGroup<Size>
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

  it('renders each toggle as a real <button> (not a hidden input)', () => {
    render(
      <ToggleGroup<Size>
        label="Taille"
        value="small"
        onValueChange={() => {}}
        options={options}
      />,
    );
    const moyen = screen.getByRole('radio', { name: 'Moyen' });
    expect(moyen.tagName).toBe('BUTTON');
  });

  it('marks the option matching `value` as pressed via aria-checked', () => {
    render(
      <ToggleGroup<Size>
        label="Taille"
        value="medium"
        onValueChange={() => {}}
        options={options}
      />,
    );
    expect(screen.getByRole('radio', { name: 'Moyen' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('radio', { name: 'Petit' })).toHaveAttribute('aria-checked', 'false');
  });

  it('fires onValueChange with the new value when a different option is picked', async () => {
    const onValueChange = vi.fn();
    render(
      <ToggleGroup<Size>
        label="Taille"
        value="small"
        onValueChange={onValueChange}
        options={options}
      />,
    );
    // Items are real `<button>` elements (no hidden input). jsdom does
    // not focus a button on `fireEvent.click`, and zag's toggle-group
    // ignores click events on un-focused items — so call the native
    // `HTMLElement.click()` after focusing, matching the helper called
    // out in the frontend playbook.
    const item = screen.getByRole('radio', { name: 'Grand' }) as HTMLButtonElement;
    await act(async () => { item.focus(); item.click(); });
    expect(onValueChange).toHaveBeenCalledWith('large');
  });

  it('does not fire onValueChange when the user clicks the already-selected option', async () => {
    // Single-select toggle groups still surface a "deselect" event from
    // zag (the user clicked the pressed item to toggle it off, leaving an
    // empty `value: []`). The primitive intentionally swallows that case
    // so the picker behaves like a radio group — one option is always
    // selected. This test pins down that contract.
    const onValueChange = vi.fn();
    render(
      <ToggleGroup<Size>
        label="Taille"
        value="medium"
        onValueChange={onValueChange}
        options={options}
      />,
    );
    const item = screen.getByRole('radio', { name: 'Moyen' }) as HTMLButtonElement;
    await act(async () => { item.focus(); item.click(); });
    expect(onValueChange).not.toHaveBeenCalled();
  });
});
