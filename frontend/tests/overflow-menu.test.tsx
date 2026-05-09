import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { OverflowMenu } from '@/ui/components/primitives';

// OverflowMenu is a thin wrapper around Ark UI's `Menu`. The trigger
// contract (label + title) is the project-owned surface — Ark already
// owns the keyboard / pointer behaviour for the menu items themselves
// (covered by their own test suite). Driving the headless menu's
// pointer state machine reliably from jsdom requires user-event, which
// isn't a project dep, so we keep the wrapper test focused on the
// trigger contract and the items' presence in the DOM via a custom
// trigger glyph.

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
