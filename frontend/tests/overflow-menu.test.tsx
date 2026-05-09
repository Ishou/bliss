import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { OverflowMenu } from '@/ui/components/primitives';

// user-event is not a project dep — pointer state machine is Ark's; test trigger contract only.
describe('OverflowMenu', () => {
  it('renders a labeled trigger with a title attribute', () => {
    render(
      <OverflowMenu
        triggerLabel="Plus d'actions"
        items={[{ id: 'a', label: 'Action A', onSelect: () => {} }]}
      />,
    );
    const trigger = screen.getByRole('button', { name: "Plus d'actions" });
    expect(trigger).toHaveAttribute('title', "Plus d'actions");
  });

  it('honours a custom trigger icon override', () => {
    render(
      <OverflowMenu
        triggerLabel="Ouvrir le menu"
        triggerIcon={<svg data-testid="custom-trigger" />}
        items={[{ id: 'a', label: 'A', onSelect: vi.fn() }]}
      />,
    );
    expect(screen.getByTestId('custom-trigger')).toBeInTheDocument();
  });
});
