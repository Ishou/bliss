import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TextField } from '@/ui/components/primitives';

// Smoke tests for the project-local `TextField` primitive (ADR-0002 §2).
// The wrapper around Ark's `Field` is responsible for wiring the label
// to the input (so screen readers announce the field), forwarding
// `onChange`, and rendering an `aria-describedby`-linked error message
// when `errorText` is provided.
describe('TextField', () => {
  it('associates the label with the input so screen readers announce it', () => {
    render(<TextField label="Pseudonyme" defaultValue="" />);
    // `getByLabelText` only matches if the `<label>` is associated with
    // an input via `htmlFor`/`id` — exactly what the Ark Field root + label
    // wires up automatically. This is the assertion that protects the
    // accessibility property the primitive promises.
    expect(screen.getByLabelText('Pseudonyme')).toBeInTheDocument();
  });

  it('fires onChange when the user types', () => {
    const onChange = vi.fn();
    render(<TextField label="Pseudonyme" defaultValue="" onChange={onChange} />);
    const input = screen.getByLabelText('Pseudonyme') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'Joueur 42' } });
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(input.value).toBe('Joueur 42');
  });

  it('renders the error text and marks the input as invalid when invalid', () => {
    render(
      <TextField
        label="Pseudonyme"
        defaultValue=""
        invalid
        errorText="Trop court"
      />,
    );
    const input = screen.getByLabelText('Pseudonyme');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText('Trop court')).toBeInTheDocument();
  });

  it('does not render the error text when not provided', () => {
    render(<TextField label="Pseudonyme" defaultValue="" />);
    expect(screen.queryByText(/.+/, { selector: '[data-part="error-text"]' })).toBeNull();
  });
});
