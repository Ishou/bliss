import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Button } from '@/ui/components/primitives';

// Smoke tests for the project-local `Button` primitive (ADR-0002 §2).
// Behavioural surface: renders an accessible button, fires onClick,
// honours `disabled`, and accepts a `variant` for styling. The exact
// styling (Panda class names) is owned by the design system and not
// tested here.
describe('Button', () => {
  it('renders a button with the accessible name from its children', () => {
    render(<Button>Cliquer</Button>);
    expect(screen.getByRole('button', { name: 'Cliquer' })).toBeInTheDocument();
  });

  it('fires onClick when clicked', () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Cliquer</Button>);
    fireEvent.click(screen.getByRole('button', { name: 'Cliquer' }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('does not fire onClick when disabled', () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick} disabled>Cliquer</Button>);
    const button = screen.getByRole('button', { name: 'Cliquer' });
    expect(button).toBeDisabled();
    fireEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('defaults to type="button" so it does not submit a parent form', () => {
    render(<Button>X</Button>);
    expect(screen.getByRole('button', { name: 'X' })).toHaveAttribute('type', 'button');
  });

  it('renders each visual variant without throwing', () => {
    const variants = ['primary', 'secondary', 'ghost'] as const;
    for (const variant of variants) {
      const { unmount } = render(<Button variant={variant}>{variant}</Button>);
      expect(screen.getByRole('button', { name: variant })).toBeInTheDocument();
      unmount();
    }
  });
});
