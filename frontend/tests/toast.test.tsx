import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Toast, ToastProvider, useToast } from '@/ui/components/primitives/Toast';

// Test harness — a button that calls `show()` on click so the toast is
// only mounted from inside a `<ToastProvider>` (matches production).
function Harness({
  toast,
  buttonLabel = 'show',
}: {
  readonly toast: Parameters<ReturnType<typeof useToast>['show']>[0];
  readonly buttonLabel?: string;
}) {
  const { show, dismiss } = useToast();
  return (
    <>
      <button type="button" onClick={() => show(toast)}>{buttonLabel}</button>
      <button type="button" onClick={dismiss}>dismiss</button>
    </>
  );
}

const renderWithProvider = (ui: React.ReactElement) =>
  render(
    <ToastProvider>
      {ui}
      <Toast />
    </ToastProvider>,
  );

beforeEach(() => { vi.useFakeTimers(); });
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('Toast', () => {
  it('renders nothing when no toast has been shown', () => {
    renderWithProvider(<Harness toast={{ text: 'unused' }} />);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('renders an info toast with role="status" when show is called', () => {
    renderWithProvider(<Harness toast={{ text: 'Reconnexion…', tone: 'info' }} />);
    act(() => { screen.getByText('show').click(); });
    const live = screen.getByRole('status');
    expect(live).toHaveTextContent('Reconnexion…');
  });

  it('renders an error toast with role="alert" when tone is error', () => {
    renderWithProvider(<Harness toast={{ text: 'Erreur', tone: 'error' }} />);
    act(() => { screen.getByText('show').click(); });
    expect(screen.getByRole('alert')).toHaveTextContent('Erreur');
  });

  it('auto-dismisses after the configured duration (default 6 s)', () => {
    renderWithProvider(<Harness toast={{ text: 'Bonjour', tone: 'info' }} />);
    act(() => { screen.getByText('show').click(); });
    expect(screen.getByRole('status')).toBeInTheDocument();
    act(() => { vi.advanceTimersByTime(6000); });
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('does NOT auto-dismiss when duration is null (sticky)', () => {
    renderWithProvider(
      <Harness toast={{ text: 'Reconnexion…', tone: 'info', duration: null }} />,
    );
    act(() => { screen.getByText('show').click(); });
    act(() => { vi.advanceTimersByTime(60_000); });
    expect(screen.getByRole('status')).toHaveTextContent('Reconnexion…');
  });

  it('dismisses when the Fermer button is clicked', () => {
    renderWithProvider(<Harness toast={{ text: 'Hello', tone: 'info' }} />);
    act(() => { screen.getByText('show').click(); });
    fireEvent.click(screen.getByRole('button', { name: /fermer/i }));
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('replaces the current toast when show is called again', () => {
    function TwoToasts() {
      const { show } = useToast();
      return (
        <>
          <button type="button" onClick={() => show({ text: 'first', tone: 'info' })}>
            show-first
          </button>
          <button type="button" onClick={() => show({ text: 'second', tone: 'info' })}>
            show-second
          </button>
        </>
      );
    }
    render(
      <ToastProvider>
        <TwoToasts />
        <Toast />
      </ToastProvider>,
    );
    act(() => { screen.getByText('show-first').click(); });
    expect(screen.getByRole('status')).toHaveTextContent('first');
    act(() => { screen.getByText('show-second').click(); });
    const status = screen.getByRole('status');
    expect(status).toHaveTextContent('second');
    expect(status).not.toHaveTextContent('first');
  });

  it('exposes dismiss via the hook', () => {
    renderWithProvider(<Harness toast={{ text: 'Hi', tone: 'info', duration: null }} />);
    act(() => { screen.getByText('show').click(); });
    expect(screen.getByRole('status')).toBeInTheDocument();
    act(() => { screen.getByText('dismiss').click(); });
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
