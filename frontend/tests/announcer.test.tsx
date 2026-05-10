import { act, render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AnnouncerProvider, useAnnouncer } from '@/ui/components/a11y/Announcer';

function Harness({ onSay }: { onSay: (say: (text: string, opts?: { assertive?: boolean }) => void) => void }) {
  const announcer = useAnnouncer();
  onSay(announcer.say);
  return null;
}

describe('Announcer', () => {
  it('renders two live regions: polite and assertive', () => {
    const { container } = render(
      <AnnouncerProvider>
        <div />
      </AnnouncerProvider>,
    );
    const polite = container.querySelector('[role="status"][aria-live="polite"]');
    const assertive = container.querySelector('[role="alert"][aria-live="assertive"]');
    expect(polite).not.toBeNull();
    expect(assertive).not.toBeNull();
  });

  it('say(text) writes to the polite region', () => {
    let say!: (t: string, o?: { assertive?: boolean }) => void;
    const { container } = render(
      <AnnouncerProvider>
        <Harness onSay={(s) => { say = s; }} />
      </AnnouncerProvider>,
    );
    act(() => { say('coucou'); });
    expect(container.querySelector('[role="status"]')?.textContent).toBe('coucou');
    expect(container.querySelector('[role="alert"]')?.textContent).toBe('');
  });

  it('say(text, { assertive: true }) writes to the assertive region', () => {
    let say!: (t: string, o?: { assertive?: boolean }) => void;
    const { container } = render(
      <AnnouncerProvider>
        <Harness onSay={(s) => { say = s; }} />
      </AnnouncerProvider>,
    );
    act(() => { say('erreur', { assertive: true }); });
    expect(container.querySelector('[role="alert"]')?.textContent).toBe('erreur');
  });

  it('de-duplicates identical messages within 200ms (per channel)', () => {
    vi.useFakeTimers();
    let say!: (t: string, o?: { assertive?: boolean }) => void;
    const { container } = render(
      <AnnouncerProvider>
        <Harness onSay={(s) => { say = s; }} />
      </AnnouncerProvider>,
    );
    act(() => { say('même'); });
    expect(container.querySelector('[role="status"]')?.textContent).toBe('même');
    // Clear and try to re-emit identical text within 200ms — should be skipped (text stays empty after clear).
    act(() => {
      container.querySelector('[role="status"]')!.textContent = '';
      say('même');
    });
    expect(container.querySelector('[role="status"]')?.textContent).toBe('');
    // After 200ms, identical text emits again.
    act(() => { vi.advanceTimersByTime(250); say('même'); });
    expect(container.querySelector('[role="status"]')?.textContent).toBe('même');
    vi.useRealTimers();
  });
});
